package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S300 forgotten password, end to end on the hosted pages and the API: neutral answers, the code from the outbox
 * (notifications renders it; locally it lands in Mailpit), wrong and expired codes, the lockout, signing out
 * everywhere, the "password changed" event and the app's own sign-in page afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class PasswordResetIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String STOREFRONT_CALLBACK = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    private static final String SELLER_CALLBACK = "http://localhost:3001/api/bff/v1/auth/oidc/callback";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final String NEW_PASSWORD = "Suya-Spice-Market-2027";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    PasswordResetIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void resetFromSellerCenterSignsOutEverywhereAndReturnsToSellerCenterSignIn() {
        String email = verifiedAccount();
        // Signed in elsewhere first: a storefront session with a refresh token, and the browser's identity session.
        BrowserSession elsewhere = new BrowserSession(base());
        Tokens before = signInTo(elsewhere, email, PASSWORD, STOREFRONT_CALLBACK);

        // Seller Center's sign-in page → "Forgot password?" (branded for Seller Center all the way).
        BrowserSession s = new BrowserSession(base());
        String verifier = BrowserSession.randomVerifier();
        String login = BrowserSession.location(s.get(authorizeUrl(SELLER_CALLBACK, verifier)));
        assertThat(login).endsWith("/login");
        HttpResponse<String> forgot = s.get(base() + "/login/forgot");
        assertThat(forgot.body()).contains("data-app=\"seller\"", "Forgot your password?", "Send code");
        HttpResponse<String> asked = s.form("/login/forgot", Map.of("email", email.toUpperCase(),
                "_csrf", BrowserSession.csrfOf(forgot.body())));
        assertThat(BrowserSession.location(asked)).endsWith("/login/forgot/code");
        HttpResponse<String> codePage = s.get(base() + "/login/forgot/code");
        assertThat(codePage.body()).contains("data-app=\"seller\"", "Set a new password", "k••••••@example.com",
                "It expires in 10 minutes", "data-password-rules", "signs you out on every device");
        String code = latestCode(email);

        // A weak password is refused without using the code; then the right code and a good password.
        HttpResponse<String> weak = s.form("/login/forgot/code", Map.of("code", code, "password", "short",
                "confirmPassword", "short", "_csrf", BrowserSession.csrfOf(codePage.body())));
        assertThat(weak.statusCode()).isEqualTo(200);
        assertThat(weak.body()).contains("Use at least 12 characters");
        HttpResponse<String> done = s.form("/login/forgot/code", Map.of("code", code, "password", NEW_PASSWORD,
                "confirmPassword", NEW_PASSWORD, "_csrf", BrowserSession.csrfOf(weak.body())));
        assertThat(BrowserSession.location(done)).endsWith("/login?reset");

        // Seller Center's sign-in page with the success message and the email filled in; signing in continues the
        // same Seller Center request.
        HttpResponse<String> signIn = s.get(absolute(BrowserSession.location(done)));
        assertThat(signIn.body()).contains("Sign in to Seller Center", "Your password was changed",
                "value=\"" + email + "\"").doesNotContain("Staff console");
        HttpResponse<String> posted = s.form("/login", Map.of("username", email, "password", NEW_PASSWORD,
                "_csrf", BrowserSession.csrfOf(signIn.body())));
        assertThat(BrowserSession.location(s.get(absolute(BrowserSession.location(posted)))))
                .startsWith(SELLER_CALLBACK + "?code=");

        // Signed out everywhere: the old refresh token is dead and the other browser must sign in again.
        assertThat(elsewhere.refreshConfidential(BFF, BFF_SECRET, before.refreshToken()).status()).isEqualTo(400);
        assertThat(BrowserSession.location(elsewhere.get(authorizeUrl(SELLER_CALLBACK,
                BrowserSession.randomVerifier())))).endsWith("/login");
        // The old password no longer works; the "password changed" email is on its way; both steps are audited.
        BrowserSession old = new BrowserSession(base());
        String oldLogin = BrowserSession.location(old.get(authorizeUrl(STOREFRONT_CALLBACK,
                BrowserSession.randomVerifier())));
        HttpResponse<String> rejected = old.form("/login", Map.of("username", email, "password", PASSWORD,
                "_csrf", BrowserSession.csrfOf(old.get(absolute(oldLogin)).body())));
        assertThat(BrowserSession.location(rejected)).contains("/login?error");
        assertThat(jdbc.sql("""
                select payload->>'method' from platform_outbox
                where type = 'identity.password.changed' and payload->'recipient'->>'email' = :e
                """).param("e", email).query(String.class).single()).isEqualTo("RESET");
        assertThat(jdbc.sql("""
                select outcome from sign_in_audit where email = :e and method = 'PASSWORD_RESET' order by id
                """).param("e", email).query(String.class).list()).containsExactly("REQUESTED", "SUCCEEDED");
        // The code is single use and hashed at rest.
        assertThat(jdbc.sql("select count(*) from verification_code where code_hash = :c")
                .param("c", code).query(Integer.class).single()).isZero();
        assertThat(api("/v1/password-reset/confirm", confirmJson(email, code, NEW_PASSWORD + "x")).statusCode())
                .isEqualTo(422);
    }

    @Test
    void theAnswerIsTheSameWithOrWithoutAnAccount() {
        String email = verifiedAccount();
        HttpResponse<String> known = api("/v1/password-reset", "{\"email\":\"" + email + "\"}");
        HttpResponse<String> unknown = api("/v1/password-reset", "{\"email\":\"nobody." + UUID.randomUUID()
                + "@example.com\"}");
        assertThat(known.statusCode()).isEqualTo(unknown.statusCode()).isEqualTo(202);
        assertThat(known.body()).isEqualTo(unknown.body()).contains("\"codeTtlMinutes\":10");
        // Hosted pages: both go on to the same code page.
        for (String e : List.of(email, "nobody." + UUID.randomUUID() + "@example.com")) {
            BrowserSession s = new BrowserSession(base());
            HttpResponse<String> asked = s.form("/login/forgot", Map.of("email", e,
                    "_csrf", BrowserSession.csrfOf(s.get(base() + "/login/forgot").body())));
            assertThat(BrowserSession.location(asked)).endsWith("/login/forgot/code");
            assertThat(s.get(base() + "/login/forgot/code").body()).contains("If ", "has an AfroTransact account");
        }
        // Only the real account got a code; a confirm for the unknown email fails like a wrong code.
        assertThat(jdbc.sql("""
                select count(*) from platform_outbox where type = 'identity.verification.requested'
                and payload->>'purpose' = 'PASSWORD_RESET' and payload->'recipient'->>'email' like 'nobody.%'
                """).query(Integer.class).single()).isZero();
        HttpResponse<String> nobody = api("/v1/password-reset/confirm", confirmJson("nobody@example.com", "123456",
                NEW_PASSWORD));
        HttpResponse<String> wrong = api("/v1/password-reset/confirm", confirmJson(email,
                latestCode(email).equals("123456") ? "654321" : "123456", NEW_PASSWORD));
        assertThat(nobody.statusCode()).isEqualTo(wrong.statusCode()).isEqualTo(422);
        assertThat(BrowserSession.json(nobody.body()).get("code").asString())
                .isEqualTo(BrowserSession.json(wrong.body()).get("code").asString())
                .isEqualTo("identity.invalid-reset-code");
    }

    @Test
    void anExpiredCodeIsRefusedLikeAWrongOne() {
        String email = verifiedAccount();
        assertThat(api("/v1/password-reset", "{\"email\":\"" + email + "\"}").statusCode()).isEqualTo(202);
        String code = latestCode(email);
        jdbc.sql("""
                update verification_code set expires_at = now() - interval '1 minute'
                where purpose = 'PASSWORD_RESET' and user_id = (select id from user_account where email = :e)
                """).param("e", email).update();
        HttpResponse<String> expired = api("/v1/password-reset/confirm", confirmJson(email, code, NEW_PASSWORD));
        assertThat(expired.statusCode()).isEqualTo(422);
        assertThat(BrowserSession.json(expired.body()).get("code").asString()).isEqualTo("identity.invalid-reset-code");
        // The password did not change.
        BrowserSession s = new BrowserSession(base());
        signInTo(s, email, PASSWORD, STOREFRONT_CALLBACK);
    }

    @Test
    void wrongCodesLockTheEmailOutAndRequestsAreLimited() {
        String email = verifiedAccount();
        api("/v1/password-reset", "{\"email\":\"" + email + "\"}");
        String wrong = latestCode(email).equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            assertThat(api("/v1/password-reset/confirm", confirmJson(email, wrong, NEW_PASSWORD)).statusCode())
                    .isEqualTo(422);
        }
        HttpResponse<String> locked = api("/v1/password-reset/confirm", confirmJson(email, wrong, NEW_PASSWORD));
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(BrowserSession.json(locked.body()).get("code").asString()).isEqualTo("identity.reset-locked");
        // The hosted page says so too.
        BrowserSession s = new BrowserSession(base());
        s.form("/login/forgot", Map.of("email", email, "_csrf",
                BrowserSession.csrfOf(s.get(base() + "/login/forgot").body())));
        HttpResponse<String> page = s.get(base() + "/login/forgot/code");
        HttpResponse<String> blocked = s.form("/login/forgot/code", Map.of("code", latestCode(email),
                "password", NEW_PASSWORD, "confirmPassword", NEW_PASSWORD, "_csrf",
                BrowserSession.csrfOf(page.body())));
        assertThat(blocked.body()).contains("Too many attempts. Try again in");

        // Requests: five per email within the window, then 429 for that email whether or not it has an account.
        String unknown = "nobody." + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) {
            assertThat(api("/v1/password-reset", "{\"email\":\"" + unknown + "\"}").statusCode()).isEqualTo(202);
        }
        assertThat(api("/v1/password-reset", "{\"email\":\"" + unknown + "\"}").statusCode()).isEqualTo(429);
        assertThat(jdbc.sql("select count(*) from sign_in_audit where email = :e and outcome = 'BLOCKED'")
                .param("e", unknown).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aSocialOnlyAccountIsToldToUseItsProvider() {
        String email = "ngozi." + UUID.randomUUID() + "@example.com";
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into user_account (id, first_name, last_name, email, email_verified, password_hash, enabled,
                                          sms_consent, created_at, updated_at, version)
                values (:id, 'Ngozi', 'Eze', :e, true, null, true, false, now(), now(), 0)
                """).param("id", id).param("e", email).update();
        jdbc.sql("""
                insert into federated_identity (provider, subject, user_id, email, linked_at)
                values ('google', :s, :id, :e, now())
                """).param("s", "g-" + id).param("id", id).param("e", email).update();
        assertThat(api("/v1/password-reset", "{\"email\":\"" + email + "\"}").statusCode()).isEqualTo(202);
        assertThat(jdbc.sql("""
                select payload->'providers'->>0 from platform_outbox
                where type = 'identity.password-reset.federated' and payload->'recipient'->>'email' = :e
                """).param("e", email).query(String.class).single()).isEqualTo("Google");
        assertThat(jdbc.sql("""
                select count(*) from platform_outbox where type = 'identity.verification.requested'
                and payload->'recipient'->>'email' = :e
                """).param("e", email).query(Integer.class).single()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<String> api(String path, String json) {
        return new BrowserSession(base()).api("POST", path, null, json);
    }

    private static String confirmJson(String email, String code, String password) {
        return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + password
                + "\",\"confirmPassword\":\"" + password + "\"}";
    }

    private String latestCode(String email) {
        return jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and payload->>'purpose' = 'PASSWORD_RESET'
                  and payload->'recipient'->>'email' = :e
                order by occurred_at desc limit 1
                """).param("e", email).query(String.class).single();
    }

    private Tokens signInTo(BrowserSession s, String email, String password, String callback) {
        String verifier = BrowserSession.randomVerifier();
        String loginPage = BrowserSession.location(s.get(authorizeUrl(callback, verifier)));
        HttpResponse<String> page = s.get(absolute(loginPage));
        HttpResponse<String> posted = s.form("/login", Map.of("username", email, "password", password,
                "_csrf", BrowserSession.csrfOf(page.body())));
        String issued = BrowserSession.location(s.get(absolute(BrowserSession.location(posted))));
        assertThat(issued).startsWith(callback + "?code=");
        Tokens t = s.exchangeConfidential(BFF, BFF_SECRET, callback, BrowserSession.code(issued), verifier);
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    private String verifiedAccount() {
        BrowserSession s = new BrowserSession(base());
        String email = "kofi." + UUID.randomUUID() + "@example.com";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("firstName", "Kofi");
        form.put("lastName", "Mensah");
        form.put("email", email);
        form.put("phoneRegion", "US");
        form.put("phone", "");
        form.put("password", PASSWORD);
        form.put("confirmPassword", PASSWORD);
        form.put("termsAccepted", "true");
        form.put("_csrf", BrowserSession.csrfOf(s.get(base() + "/signup").body()));
        assertThat(BrowserSession.location(s.form("/signup", form))).endsWith("/signup/verify");
        String code = jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and payload->'recipient'->>'email' = :e
                order by occurred_at desc limit 1
                """).param("e", email).query(String.class).single();
        HttpResponse<String> verified = s.form("/signup/verify", Map.of("code", code,
                "_csrf", BrowserSession.csrfOf(s.get(base() + "/signup/verify").body())));
        assertThat(BrowserSession.location(verified)).isEqualTo("http://localhost:3000");
        return email;
    }

    private String authorizeUrl(String redirectUri, String verifier) {
        return base() + "/oauth2/authorize?response_type=code&client_id=" + BFF + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid profile email offline_access") + "&state=st-" + UUID.randomUUID()
                + "&nonce=n-1&code_challenge=" + BrowserSession.challenge(verifier) + "&code_challenge_method=S256";
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : base() + location;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
