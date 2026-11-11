package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.identity.domain.DefaultTotpAuthenticator;
import com.afrotransact.identity.it.BrowserSession.AuthorizeResult;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Sign-up and MFA end to end on the hosted pages and the first-party API: sign-up, email code, then the optional
 * "Secure your account" step; no OTP is ever sent to someone who did not enrol a factor; email codes and the
 * authenticator work as real factors; mandatory MFA (staff) forces enrolment first instead of sending a code.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs the test on that PostgreSQL instead of a container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
class SignUpMfaFlowIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? PostgresTestContainer.create() : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    private static final String STOREFRONT = "customer-app";
    private static final String STOREFRONT_REDIRECT = "http://localhost:8081/auth/callback";
    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final Pattern SECRET = Pattern.compile("id=\"totp-secret\"[^>]*>([A-Z2-7]+)<");
    private static final Pattern RECOVERY = Pattern.compile("<code>([a-z2-9]{5}-[a-z2-9]{5})</code>");

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    SignUpMfaFlowIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** Hosted sign-up through the emailed code lands on "Secure your account"; Skip continues; sign-in is password only. */
    @Test
    void skippingTheSetupMeansPasswordOnlySignInAndNoCodeIsEverSent() {
        BrowserSession s = new BrowserSession(base());
        String email = "skip." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> setup = signUpAndVerify(s, email);
        assertThat(setup.body()).contains("Secure your account").contains("Skip for now")
                .contains("Authenticator app").contains("Email codes").doesNotContain("Text message");

        HttpResponse<String> skipped = s.submit("/login/mfa/setup/skip", setup, Map.of());
        assertThat(skipped.statusCode()).isEqualTo(302);
        assertThat(jdbc.sql("select count(*) from mfa_setup_prompt where user_id = (select id from user_account "
                + "where email = :e)").param("e", email).query(Integer.class).single()).isEqualTo(1);

        int codesBefore = outboxCodes(email);
        BrowserSession later = new BrowserSession(base());
        AuthorizeResult result = later.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        assertThat(result.mfaRequired()).isFalse();
        assertThat(result.code()).isNotBlank();
        assertThat(outboxCodes(email)).isEqualTo(codesBefore);
    }

    /** Turning on email codes right after sign-up: the next sign-in needs (and is sent) the emailed code. */
    @Test
    void emailCodesEnrolledAfterSignUpAreAskedAtNextSignIn() {
        BrowserSession s = new BrowserSession(base());
        String email = "mail." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> setup = signUpAndVerify(s, email);

        HttpResponse<String> sent = s.submit("/login/mfa/setup/email", setup, Map.of());
        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(sent.body()).contains("Enter the 6-digit code");
        String enrolCode = latestCode(email);
        HttpResponse<String> done = s.submit("/login/mfa/setup/email/confirm", sent, Map.of("code", enrolCode));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(done.body()).contains("Two-step verification is on");
        Matcher recovery = RECOVERY.matcher(done.body());
        assertThat(recovery.find()).isTrue();
        assertThat(jdbc.sql("select count(*) from mfa_email_otp where user_id = (select id from user_account where "
                + "email = :e)").param("e", email).query(Integer.class).single()).isEqualTo(1);
        assertThat(s.submit("/login/mfa/setup/finish", done, Map.of()).statusCode()).isEqualTo(302);

        BrowserSession laptop = new BrowserSession(base());
        AuthorizeResult first = laptop.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        // email is the only factor: the code was sent without asking
        String signInCode = latestCode(email);
        assertThat(signInCode).isNotEqualTo(null);
        assertThat(jdbc.sql("select count(*) from verification_code where purpose = 'SIGN_IN_EMAIL' and user_id = "
                + "(select id from user_account where email = :e)").param("e", email).query(Integer.class).single())
                .isEqualTo(1);
        String code = laptop.completeMfa("EMAIL_OTP", signInCode, false);
        Tokens tokens = laptop.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                new AuthorizeResult(code, false, first.verifier()));
        assertThat(tokens.status()).isEqualTo(200);
        assertThat(jdbc.sql("select last_used_at is not null from mfa_email_otp where user_id = (select id from "
                + "user_account where email = :e)").param("e", email).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select count(*) from access_change_log where action = 'mfa.email-otp.used'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(1);

        // the same emailed code cannot be used again
        BrowserSession replay = new BrowserSession(base());
        assertThat(replay.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD).mfaRequired()).isTrue();
        // resend is throttled (30 s), so the replayed, already used code is all there is, and it no longer works
        assertThatThrownBy(() -> replay.completeMfa("EMAIL_OTP", signInCode, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rejected");
    }

    /** The authenticator app set up on the hosted page: QR/manual key, confirm, recovery codes, then TOTP at sign-in. */
    @Test
    void authenticatorSetupOnTheHostedPageThenTotpAtSignIn() {
        BrowserSession s = new BrowserSession(base());
        String email = "totp." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> setup = signUpAndVerify(s, email);

        HttpResponse<String> qr = s.submit("/login/mfa/setup/totp", setup, Map.of());
        assertThat(qr.statusCode()).isEqualTo(200);
        assertThat(qr.body()).contains("<svg").contains("totp-secret");
        Matcher secret = SECRET.matcher(qr.body());
        assertThat(secret.find()).isTrue();
        DefaultTotpAuthenticator totp = new DefaultTotpAuthenticator();
        String code = totp.codeAt(secret.group(1), Instant.now().getEpochSecond() / 30);
        HttpResponse<String> done = s.submit("/login/mfa/setup/totp/confirm", qr, Map.of("code", code));
        assertThat(done.body()).contains("Two-step verification is on");
        assertThat(RECOVERY.matcher(done.body()).find()).isTrue();
        s.submit("/login/mfa/setup/finish", done, Map.of());

        BrowserSession laptop = new BrowserSession(base());
        AuthorizeResult first = laptop.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        // an authenticator-only person is never emailed
        assertThat(jdbc.sql("select count(*) from verification_code where purpose = 'SIGN_IN_EMAIL' and user_id = "
                + "(select id from user_account where email = :e)").param("e", email).query(Integer.class).single())
                .isZero();
        long step = Instant.now().getEpochSecond() / 30;
        String again = totp.codeAt(secret.group(1), step + 1);
        String authCode = laptop.completeMfa("TOTP", again, false);
        assertThat(laptop.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                new AuthorizeResult(authCode, false, first.verifier())).status()).isEqualTo(200);
    }

    /** Mandatory MFA (staff) with nothing enrolled: no OTP, a forced enrolment first, then the session. First-party API. */
    @Test
    void staffWithoutAFactorMustEnrolFirstAndNoCodeIsSent() {
        String bff = new BrowserSession(base()).clientCredentials(BFF, BFF_SECRET, "auth:first-party")
                .accessToken();
        BrowserSession api = new BrowserSession(base());
        int before = jdbc.sql("select count(*) from platform_outbox where type = 'identity.verification.requested'")
                .query(Integer.class).single();

        HttpResponse<String> step = api.api("POST", "/v1/auth/password", bff, signIn("admin@afrotransact.com",
                "LocalAdmin2026!"));
        assertThat(step.statusCode()).as(step.body()).isEqualTo(200);
        JsonNode body = BrowserSession.json(step.body());
        assertThat(body.get("status").asString()).isEqualTo("MFA_ENROLMENT_REQUIRED");
        assertThat(body.has("loginTicket")).isFalse();
        assertThat(body.get("factors").get("enrolMethods").toString()).contains("TOTP").contains("EMAIL_OTP");
        // no OTP went out (the admin has a phone on file: the old SMS fallback must not fire)
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'identity.verification.requested'")
                .query(Integer.class).single()).isEqualTo(before);
        String challenge = body.get("challengeId").asString();

        // a code for a challenge that has nothing enrolled is refused
        HttpResponse<String> wrong = api.api("POST", "/v1/auth/mfa/verify", bff, """
                {"challengeId":"%s","factor":"EMAIL_OTP","code":"123456","trustDevice":false,"clientIp":"203.0.113.7"}""".formatted(
                challenge));
        assertThat(wrong.statusCode()).isIn(400, 401, 422);

        JsonNode totpSetup = BrowserSession.json(api.api("POST", "/v1/auth/mfa/enrol/totp", bff, """
                {"challengeId":"%s","clientIp":"203.0.113.7"}""".formatted(challenge)).body());
        String secret = totpSetup.get("secret").asString();
        assertThat(totpSetup.get("otpauthUri").asString()).startsWith("otpauth://totp/");
        String code = new DefaultTotpAuthenticator().codeAt(secret, Instant.now().getEpochSecond() / 30);
        HttpResponse<String> done = api.api("POST", "/v1/auth/mfa/enrol/totp/confirm", bff, """
                {"challengeId":"%s","code":"%s","clientIp":"203.0.113.7"}""".formatted(challenge, code));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        JsonNode completed = BrowserSession.json(done.body());
        assertThat(completed.get("recoveryCodes").size()).isEqualTo(10);
        assertThat(completed.get("step").get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(completed.get("step").get("loginTicket").asString()).isNotBlank();

        // from now on the admin answers the authenticator
        HttpResponse<String> next = api.api("POST", "/v1/auth/password", bff, signIn("admin@afrotransact.com",
                "LocalAdmin2026!"));
        assertThat(BrowserSession.json(next.body()).get("status").asString()).isEqualTo("MFA_REQUIRED");
    }

    /** First-party sign-in for a plain customer: password only; the step says setup is suggested (gentle reminder). */
    @Test
    void firstPartyPasswordSignInNeedsNoCodeAndSuggestsSetup() {
        BrowserSession s = new BrowserSession(base());
        String email = "api." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, """
                {"firstName":"Ada","lastName":"Okafor","email":"%s","phone":"(713) 249-0142","phoneRegion":"US",
                 "password":"%s","confirmPassword":"%s","termsAccepted":true,"smsConsent":false,"intent":"shop"}
                """.formatted(email, PASSWORD, PASSWORD));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(s.api("POST", "/v1/accounts/verification", null,
                "{\"email\":\"" + email + "\",\"code\":\"" + latestCode(email) + "\"}").statusCode()).isEqualTo(204);
        String bff = new BrowserSession(base()).clientCredentials(BFF, BFF_SECRET, "auth:first-party")
                .accessToken();
        int before = outboxCodes(email);

        JsonNode step = BrowserSession.json(s.api("POST", "/v1/auth/password", bff, signIn(email, PASSWORD)).body());

        assertThat(step.get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(step.get("mfaSetupSuggested").asBoolean()).isTrue();
        assertThat(outboxCodes(email)).isEqualTo(before);
    }

    private HttpResponse<String> signUpAndVerify(BrowserSession s, String email) {
        String verifier = s.beginAuthorize(STOREFRONT, STOREFRONT_REDIRECT);
        assertThat(verifier).isNotBlank();
        HttpResponse<String> form = s.page("/signup");
        assertThat(form.statusCode()).isEqualTo(200);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("firstName", "Adaeze");
        fields.put("lastName", "Okafor");
        fields.put("email", email);
        fields.put("phone", "(713) 249-0142");
        fields.put("phoneRegion", "US");
        fields.put("password", PASSWORD);
        fields.put("confirmPassword", PASSWORD);
        fields.put("termsAccepted", "true");
        HttpResponse<String> posted = s.submit("/signup", form, fields);
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(302);
        HttpResponse<String> verifyPage = s.page("/signup/verify");
        assertThat(verifyPage.statusCode()).isEqualTo(200);
        HttpResponse<String> verified = s.submit("/signup/verify", verifyPage, Map.of("code", latestCode(email)));
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(302);
        assertThat(BrowserSession.location(verified)).contains("/login/mfa/setup");
        HttpResponse<String> setup = s.page("/login/mfa/setup");
        assertThat(setup.statusCode()).isEqualTo(200);
        return setup;
    }

    private int outboxCodes(String email) {
        return jdbc.sql("select count(*) from platform_outbox where type = 'identity.verification.requested' and "
                + "payload->'recipient'->>'email' = :e").param("e", email).query(Integer.class).single();
    }

    private String latestCode(String email) {
        return jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and payload->'recipient'->>'email' = :e
                order by occurred_at desc limit 1
                """).param("e", email).query(String.class).single();
    }

    private static String signIn(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"clientIp\":\"203.0.113.7\"}";
    }
}
