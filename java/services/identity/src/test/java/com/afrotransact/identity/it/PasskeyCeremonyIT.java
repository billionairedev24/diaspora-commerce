package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.it.BrowserSession.AuthorizeResult;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.identity.testsupport.VirtualAuthenticator;
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
import tools.jackson.databind.JsonNode;

/**
 * S203: full WebAuthn ceremonies against the running identity service with a pure-Java virtual authenticator
 * ({@link VirtualAuthenticator}: EC P-256 key pair, hand-built CBOR "none" attestation and ES256 assertions), over real
 * HTTP and PostgreSQL. Registration goes through the hosted ceremony ({@code /webauthn/register/options} →
 * {@code /webauthn/register}); sign-in through both the hosted page ({@code /webauthn/authenticate/options} →
 * {@code /login/webauthn} → authorization code → tokens) and the first-party API ({@code /v1/auth/passkey/options} →
 * {@code /verify} → login ticket → tokens). Covers the signature counter, wrong origins, replayed challenges, a cloned
 * authenticator and a removed passkey.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox",
    "at.identity.webauthn.app-origins=" + PasskeyCeremonyIT.ANDROID_ORIGIN})
@ActiveProfiles("local")
@Testcontainers
class PasskeyCeremonyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    /** Origins in {@code at.identity.webauthn.allowed-origins} (local profile): identity's pages and the storefront. */
    private static final String IDENTITY_ORIGIN = "http://localhost:8100";
    private static final String STOREFRONT_ORIGIN = "http://localhost:3000";
    private static final String PHISHING_ORIGIN = "https://afrotransact-login.example";
    private static final String RP_ID = "localhost";
    private static final String STOREFRONT = "storefront";
    private static final String STOREFRONT_REDIRECT = "http://localhost:3000/auth/callback";
    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String BFF_REDIRECT = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    private static final String CLIENT = "\"clientIp\":\"198.51.100.30\",\"userAgent\":\"VirtualAuthenticator/1.0\"";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    /** S016: the customer app's Android origin (base64url SHA-256 of its signing certificate). */
    static final String ANDROID_ORIGIN = "android:apk-key-hash:Sxm7AqJ9uVt8bX0bYk1pLZ4Qe3oW2nHcR6dF5gT7yU8";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    PasskeyCeremonyIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void registersThenSignsInOnTheHostedPageAndTheFirstPartyApiWithARisingSignCount() {
        Account account = newAccount();
        VirtualAuthenticator authenticator = new VirtualAuthenticator(RP_ID);
        register(account, authenticator);

        JsonNode passkeys = BrowserSession.json(account.session().api("GET", "/v1/me/passkeys", account.token(), null)
                .body());
        assertThat(passkeys.size()).isEqualTo(1);
        assertThat(passkeys.get(0).get("credentialId").asString()).isEqualTo(authenticator.credentialId());
        assertThat(storedSignCount(authenticator)).isZero();

        List<Long> counts = new ArrayList<>();
        Tokens hosted = hostedSignIn(authenticator);
        assertThat(hosted.accessClaims().get("sub").asString()).isEqualTo(account.userId());
        counts.add(storedSignCount(authenticator));

        Tokens api = apiSignIn(authenticator);
        assertThat(api.accessClaims().get("sub").asString()).isEqualTo(account.userId());
        counts.add(storedSignCount(authenticator));

        Tokens again = hostedSignIn(authenticator);
        assertThat(again.accessClaims().get("sub").asString()).isEqualTo(account.userId());
        counts.add(storedSignCount(authenticator));

        assertThat(counts).as("the stored signature counter follows the authenticator").containsExactly(1L, 2L, 3L);
        assertThat(jdbc.sql("select count(*) from sign_in_audit where user_id = :u and method = 'PASSKEY' "
                + "and outcome = 'SUCCEEDED'").param("u", UUID.fromString(account.userId())).query(Long.class)
                .single()).as("first-party passkey sign-in audited").isEqualTo(1);
    }

    @Test
    void rejectsCeremoniesFromAnotherOrigin() {
        Account account = newAccount();
        VirtualAuthenticator phished = new VirtualAuthenticator(RP_ID);
        RegistrationOptions options = registrationOptions(account.session());
        HttpResponse<String> rejectedRegistration = account.session().postJson("/webauthn/register",
                phished.create(options.challenge(), options.userHandle(), PHISHING_ORIGIN, "Phished"),
                options.csrf());
        // Spring Security lets the WebAuthn4J BadOriginException escape the registration filter (a 5xx, not a 400);
        // what matters here is that the ceremony fails and nothing is stored.
        assertThat(rejectedRegistration.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(passkeyCount(account)).as("nothing registered from a foreign origin").isZero();

        VirtualAuthenticator authenticator = new VirtualAuthenticator(RP_ID);
        register(account, authenticator);

        BrowserSession hosted = new BrowserSession(base());
        hosted.beginAuthorize(STOREFRONT, STOREFRONT_REDIRECT);
        String csrf = hosted.csrfToken();
        String challenge = hostedChallenge(hosted, csrf);
        HttpResponse<String> foreign = hosted.postJson("/login/webauthn",
                authenticator.get(challenge, PHISHING_ORIGIN), csrf);
        assertThat(foreign.statusCode()).isEqualTo(401);

        String bff = bffToken();
        ApiChallenge c = apiChallenge(bff);
        HttpResponse<String> foreignApi = verify(bff, c.challengeId(), authenticator.get(c.challenge(),
                PHISHING_ORIGIN));
        assertThat(foreignApi.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(foreignApi.body()).get("code").asString())
                .isEqualTo("identity.passkey-rejected");
        assertThat(storedSignCount(authenticator)).as("rejected assertions do not move the counter").isZero();

        assertThat(apiSignIn(authenticator).accessClaims().get("sub").asString()).isEqualTo(account.userId());
    }

    @Test
    void rejectsReplayedChallenges() {
        Account account = newAccount();
        VirtualAuthenticator authenticator = new VirtualAuthenticator(RP_ID);
        RegistrationOptions options = registrationOptions(account.session());
        String registration = authenticator.create(options.challenge(), options.userHandle(), IDENTITY_ORIGIN,
                "Laptop");
        assertThat(account.session().postJson("/webauthn/register", registration, options.csrf()).statusCode())
                .isEqualTo(200);
        assertThat(account.session().postJson("/webauthn/register", registration, account.session().csrfToken())
                .statusCode()).as("registration response replayed: its options were consumed").isEqualTo(400);
        assertThat(passkeyCount(account)).isEqualTo(1);

        // Hosted page: the challenge lives in the session and is consumed by the first assertion.
        BrowserSession hosted = new BrowserSession(base());
        hosted.beginAuthorize(STOREFRONT, STOREFRONT_REDIRECT);
        String csrf = hosted.csrfToken();
        String challenge = hostedChallenge(hosted, csrf);
        String assertion = authenticator.get(challenge, IDENTITY_ORIGIN);
        HttpResponse<String> first = hosted.postJson("/login/webauthn", assertion, csrf);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(hosted.postJson("/login/webauthn", assertion, hosted.csrfToken()).statusCode())
                .as("same assertion again in the same browser").isEqualTo(401);

        BrowserSession attacker = new BrowserSession(base());
        attacker.beginAuthorize(STOREFRONT, STOREFRONT_REDIRECT);
        String attackerCsrf = attacker.csrfToken();
        assertThat(attacker.postJson("/login/webauthn", assertion, attackerCsrf).statusCode())
                .as("captured assertion in another browser").isEqualTo(401);
        hostedChallenge(attacker, attackerCsrf);
        assertThat(attacker.postJson("/login/webauthn", authenticator.get(challenge, IDENTITY_ORIGIN), attackerCsrf)
                .statusCode()).as("old challenge signed again, after fresh options").isEqualTo(401);

        // First-party API: a challenge is good for one assertion, and only for its own challenge id.
        String bff = bffToken();
        ApiChallenge c1 = apiChallenge(bff);
        String apiAssertion = authenticator.get(c1.challenge(), STOREFRONT_ORIGIN);
        assertThat(verify(bff, c1.challengeId(), apiAssertion).statusCode()).isEqualTo(200);
        HttpResponse<String> replay = verify(bff, c1.challengeId(), apiAssertion);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(replay.body()).get("code").asString()).isEqualTo("identity.challenge-invalid");
        ApiChallenge c2 = apiChallenge(bff);
        HttpResponse<String> crossed = verify(bff, c2.challengeId(), authenticator.get(c1.challenge(),
                STOREFRONT_ORIGIN));
        assertThat(crossed.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(crossed.body()).get("code").asString()).isEqualTo("identity.passkey-rejected");
    }

    @Test
    void rejectsAClonedAuthenticatorWhoseSignCountGoesBackwards() {
        Account account = newAccount();
        VirtualAuthenticator authenticator = new VirtualAuthenticator(RP_ID);
        register(account, authenticator);
        apiSignIn(authenticator);
        apiSignIn(authenticator);
        assertThat(storedSignCount(authenticator)).isEqualTo(2);

        String bff = bffToken();
        ApiChallenge c = apiChallenge(bff);
        HttpResponse<String> stale = verify(bff, c.challengeId(), authenticator.getWithCounter(c.challenge(),
                STOREFRONT_ORIGIN, 1));
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(stale.body()).get("code").asString()).isEqualTo("identity.passkey-rejected");
        assertThat(storedSignCount(authenticator)).isEqualTo(2);
    }

    @Test
    void removingThePasskeyPreventsSignInWithIt() {
        Account account = newAccount();
        VirtualAuthenticator authenticator = new VirtualAuthenticator(RP_ID);
        register(account, authenticator);
        hostedSignIn(authenticator);

        HttpResponse<String> removed = account.session().api("DELETE", "/v1/me/passkeys/"
                + URLEncoder.encode(authenticator.credentialId(), StandardCharsets.UTF_8), account.token(), null);
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
        assertThat(passkeyCount(account)).isZero();

        BrowserSession hosted = new BrowserSession(base());
        hosted.beginAuthorize(STOREFRONT, STOREFRONT_REDIRECT);
        String csrf = hosted.csrfToken();
        assertThat(hosted.postJson("/login/webauthn", authenticator.get(hostedChallenge(hosted, csrf),
                IDENTITY_ORIGIN), csrf).statusCode()).isEqualTo(401);
        String bff = bffToken();
        ApiChallenge c = apiChallenge(bff);
        HttpResponse<String> api = verify(bff, c.challengeId(), authenticator.get(c.challenge(), STOREFRONT_ORIGIN));
        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(api.body()).get("code").asString()).isEqualTo("identity.passkey-rejected");

        AuthorizeResult password = new BrowserSession(base()).signIn(STOREFRONT, STOREFRONT_REDIRECT,
                account.email(), PASSWORD);
        assertThat(password.code()).as("the password still works").isNotBlank();
    }

    @Test
    void registersRenamesAndListsPasskeysOverTheApiForTheAppsAndSignsInWithThem() {
        Account account = newAccount();

        // A foreign origin is refused, and that registration can't be retried.
        Registration phishing = startApiRegistration(account, "Phished");
        HttpResponse<String> phished = completeApiRegistration(account, phishing.id(),
                new VirtualAuthenticator(RP_ID).credential(phishing.challenge(), phishing.userHandle(),
                        PHISHING_ORIGIN), null);
        assertThat(phished.statusCode()).as(phished.body()).isEqualTo(422);
        assertThat(BrowserSession.json(phished.body()).get("code").asString()).isEqualTo("identity.passkey-rejected");
        HttpResponse<String> retried = completeApiRegistration(account, phishing.id(),
                new VirtualAuthenticator(RP_ID).credential(phishing.challenge(), phishing.userHandle(),
                        IDENTITY_ORIGIN), null);
        assertThat(retried.statusCode()).isEqualTo(409);
        assertThat(passkeyCount(account)).isZero();

        // The storefront (through gateway-bff) registers a passkey.
        VirtualAuthenticator web = new VirtualAuthenticator(RP_ID);
        Registration first = startApiRegistration(account, "MacBook");
        HttpResponse<String> registered = completeApiRegistration(account, first.id(),
                web.credential(first.challenge(), first.userHandle(), STOREFRONT_ORIGIN), null);
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        assertThat(BrowserSession.json(registered.body()).get("credentialId").asString())
                .isEqualTo(web.credentialId());
        assertThat(BrowserSession.json(registered.body()).get("label").asString()).isEqualTo("MacBook");
        assertThat(completeApiRegistration(account, first.id(), web.credential(first.challenge(),
                first.userHandle(), STOREFRONT_ORIGIN), null).statusCode()).as("one registration per challenge")
                .isEqualTo(409);

        // The Android app registers a platform passkey (fingerprint) with its own origin; the existing one is
        // excluded so the same authenticator isn't registered twice.
        Registration second = startApiRegistration(account, null);
        assertThat(second.excluded()).containsExactly(web.credentialId());
        VirtualAuthenticator phone = new VirtualAuthenticator(RP_ID);
        HttpResponse<String> mobile = completeApiRegistration(account, second.id(),
                phone.credential(second.challenge(), second.userHandle(), ANDROID_ORIGIN), "Pixel 9");
        assertThat(mobile.statusCode()).as(mobile.body()).isEqualTo(201);
        assertThat(passkeyCount(account)).isEqualTo(2);

        // Another account can't use this account's registration.
        Account other = newAccount();
        Registration mine = startApiRegistration(account, null);
        assertThat(completeApiRegistration(other, mine.id(), new VirtualAuthenticator(RP_ID).credential(
                mine.challenge(), mine.userHandle(), STOREFRONT_ORIGIN), null).statusCode()).isEqualTo(409);

        // Both passkeys sign in through the first-party API (no second factor), the hosted page too.
        assertThat(apiSignIn(web).accessClaims().get("sub").asString()).isEqualTo(account.userId());
        assertThat(hostedSignIn(phone).accessClaims().get("sub").asString()).isEqualTo(account.userId());

        // Rename one (Login & security on web and mobile).
        HttpResponse<String> renamed = account.session().api("PATCH", "/v1/me/passkeys/" + phone.credentialId(),
                account.token(), "{\"label\":\"Work phone\"}");
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(BrowserSession.json(renamed.body()).get("label").asString()).isEqualTo("Work phone");
        assertThat(other.session().api("PATCH", "/v1/me/passkeys/" + phone.credentialId(), other.token(),
                "{\"label\":\"Mine now\"}").statusCode()).isEqualTo(404);
        JsonNode list = BrowserSession.json(account.session().api("GET", "/v1/me/passkeys", account.token(), null)
                .body());
        List<String> labels = new ArrayList<>();
        list.forEach(p -> labels.add(p.get("label").asString()));
        assertThat(labels).containsExactlyInAnyOrder("MacBook", "Work phone");
    }

    // ------------------------------------------------------------------------------------------------- ceremonies

    private record Registration(String id, String challenge, String userHandle, List<String> excluded) {
    }

    /** POST /v1/me/passkeys/registrations with the account's bearer token (the BFF or the mobile app). */
    private Registration startApiRegistration(Account account, String label) {
        HttpResponse<String> r = account.session().api("POST", "/v1/me/passkeys/registrations", account.token(),
                label == null ? "{}" : "{\"label\":\"" + label + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode o = BrowserSession.json(r.body());
        JsonNode pk = o.get("publicKey");
        assertThat(pk.get("rp").get("id").asString()).isEqualTo(RP_ID);
        assertThat(pk.get("authenticatorSelection").get("userVerification").asString()).isEqualTo("required");
        assertThat(pk.get("authenticatorSelection").get("residentKey").asString()).isEqualTo("required");
        List<String> excluded = new ArrayList<>();
        if (pk.has("excludeCredentials")) {
            pk.get("excludeCredentials").forEach(c -> excluded.add(c.get("id").asString()));
        }
        return new Registration(o.get("registrationId").asString(), pk.get("challenge").asString(),
                pk.get("user").get("id").asString(), excluded);
    }

    private HttpResponse<String> completeApiRegistration(Account account, String registrationId, String credential,
                                                         String label) {
        return account.session().api("POST", "/v1/me/passkeys", account.token(), "{\"registrationId\":\""
                + registrationId + "\",\"credential\":" + credential
                + (label == null ? "" : ",\"label\":\"" + label + "\"") + "}");
    }


    private record Account(BrowserSession session, String userId, String email, String token) {
    }

    private record RegistrationOptions(String challenge, String userHandle, String csrf) {
    }

    private record ApiChallenge(String challengeId, String challenge) {
    }

    /** A verified customer signed in with a password in {@code session} (the browser that registers the passkey). */
    private Account newAccount() {
        BrowserSession s = new BrowserSession(base());
        String email = "passkey.e2e." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, """
                {"firstName":"Kofi","lastName":"Mensah","email":"%s","phone":"(713) 249-0142","phoneRegion":"US",
                 "password":"%s","confirmPassword":"%s","termsAccepted":true,"smsConsent":false,"intent":"shop"}
                """.formatted(email, PASSWORD, PASSWORD));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String code = jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and payload->'recipient'->>'email' = :d
                order by occurred_at desc limit 1
                """).param("d", email).query(String.class).single();
        assertThat(s.api("POST", "/v1/accounts/verification", null,
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}").statusCode()).isEqualTo(204);
        Tokens t = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return new Account(s, BrowserSession.json(created.body()).get("userId").asString(), email, t.accessToken());
    }

    private RegistrationOptions registrationOptions(BrowserSession s) {
        String csrf = s.csrfToken();
        HttpResponse<String> r = s.postJson("/webauthn/register/options", "{}", csrf);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode o = BrowserSession.json(r.body());
        assertThat(o.get("rp").get("id").asString()).isEqualTo(RP_ID);
        List<Integer> algs = new ArrayList<>();
        o.get("pubKeyCredParams").forEach(p -> algs.add(p.get("alg").asInt()));
        assertThat(algs).as("ES256 offered").contains(-7);
        return new RegistrationOptions(o.get("challenge").asString(), o.get("user").get("id").asString(), csrf);
    }

    /** The hosted registration ceremony, exactly as the passkey management page's script runs it. */
    private void register(Account account, VirtualAuthenticator authenticator) {
        RegistrationOptions options = registrationOptions(account.session());
        HttpResponse<String> r = account.session().postJson("/webauthn/register",
                authenticator.create(options.challenge(), options.userHandle(), IDENTITY_ORIGIN, "Virtual passkey"),
                options.csrf());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(BrowserSession.json(r.body()).get("success").asBoolean()).isTrue();
    }

    private static String hostedChallenge(BrowserSession s, String csrf) {
        HttpResponse<String> r = s.postJson("/webauthn/authenticate/options", "{}", csrf);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode o = BrowserSession.json(r.body());
        assertThat(o.get("rpId").asString()).isEqualTo(RP_ID);
        return o.get("challenge").asString();
    }

    /** Hosted sign-in: /oauth2/authorize → sign-in page → passkey → redirectUrl → code → tokens. */
    private Tokens hostedSignIn(VirtualAuthenticator authenticator) {
        BrowserSession browser = new BrowserSession(base());
        String verifier = browser.beginAuthorize(STOREFRONT, STOREFRONT_REDIRECT);
        String csrf = browser.csrfToken();
        String challenge = hostedChallenge(browser, csrf);
        HttpResponse<String> r = browser.postJson("/login/webauthn", authenticator.get(challenge, IDENTITY_ORIGIN),
                csrf);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode result = BrowserSession.json(r.body());
        assertThat(result.get("authenticated").asBoolean()).isTrue();
        String redirect = result.get("redirectUrl").asString();
        assertThat(redirect).as("back to the saved authorization request, no second factor").contains(
                "/oauth2/authorize");
        String code = browser.resumeAuthorize(redirect);
        Tokens t = browser.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT, new AuthorizeResult(code, false, verifier));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    /** First-party sign-in as gateway-bff runs it: options → assertion → login ticket → code → tokens. */
    private Tokens apiSignIn(VirtualAuthenticator authenticator) {
        String bff = bffToken();
        ApiChallenge c = apiChallenge(bff);
        HttpResponse<String> verified = verify(bff, c.challengeId(), authenticator.get(c.challenge(),
                STOREFRONT_ORIGIN));
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
        JsonNode step = BrowserSession.json(verified.body());
        assertThat(step.get("status").asString()).isEqualTo("AUTHENTICATED");
        String verifier = BrowserSession.randomVerifier();
        HttpResponse<String> authorize = new BrowserSession(base()).get(base()
                + "/oauth2/authorize?response_type=code&client_id=" + BFF + "&redirect_uri=" + enc(BFF_REDIRECT)
                + "&scope=" + enc("openid profile email offline_access") + "&state=st-bff&nonce=n-1&code_challenge="
                + BrowserSession.challenge(verifier) + "&code_challenge_method=S256&login_ticket="
                + enc(step.get("loginTicket").asString()));
        assertThat(authorize.statusCode()).as(authorize.body()).isEqualTo(302);
        Tokens t = new BrowserSession(base()).exchangeConfidential(BFF, BFF_SECRET, BFF_REDIRECT,
                BrowserSession.code(BrowserSession.location(authorize)), verifier);
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    private ApiChallenge apiChallenge(String bff) {
        HttpResponse<String> r = new BrowserSession(base()).api("POST", "/v1/auth/passkey/options", bff,
                "{" + CLIENT + "}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode o = BrowserSession.json(r.body());
        assertThat(o.get("publicKey").get("rpId").asString()).isEqualTo(RP_ID);
        assertThat(o.get("publicKey").get("userVerification").asString()).isEqualTo("required");
        return new ApiChallenge(o.get("challengeId").asString(), o.get("publicKey").get("challenge").asString());
    }

    private HttpResponse<String> verify(String bff, String challengeId, String credential) {
        return new BrowserSession(base()).api("POST", "/v1/auth/passkey/verify", bff,
                "{\"challengeId\":\"" + challengeId + "\",\"credential\":" + credential + "," + CLIENT + "}");
    }

    private String bffToken() {
        Tokens t = new BrowserSession(base()).clientCredentials(BFF, BFF_SECRET, "auth:first-party");
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t.accessToken();
    }

    private long storedSignCount(VirtualAuthenticator authenticator) {
        return jdbc.sql("select signature_count from user_credentials where credential_id = :id")
                .param("id", authenticator.credentialId()).query(Long.class).single();
    }

    private int passkeyCount(Account account) {
        return BrowserSession.json(account.session().api("GET", "/v1/me/passkeys", account.token(), null).body())
                .size();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
