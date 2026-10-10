package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.FederatedLoginService;
import com.afrotransact.identity.application.FederatedProfile;
import com.afrotransact.identity.domain.DefaultTotpAuthenticator;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.it.BrowserSession.AuthorizeResult;
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
import tools.jackson.databind.JsonNode;

/**
 * End-to-end against real PostgreSQL over HTTP: discovery/JWKS, client credentials, unified sign-up → email code →
 * authorization code + PKCE → custom claims, refresh rotation, role-based landing, access administration taking effect
 * on the next token, staff MFA via SMS, TOTP enrolment with "trust this browser", and resend rate limits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox",
    "at.identity.social.google.client-id=it-google-client",
    "at.identity.social.google.client-secret=it-google-secret"})
@ActiveProfiles("local")
@Testcontainers
class IdentityFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String STOREFRONT = "storefront";
    private static final String STOREFRONT_REDIRECT = "http://localhost:3000/auth/callback";
    private static final String STAFF_CONSOLE = "staff-console";
    private static final String STAFF_REDIRECT = "http://localhost:3002/auth/callback";
    private static final String ADMIN_EMAIL = "admin@afrotransact.com";
    private static final String ADMIN_PASSWORD = "LocalAdmin2026!";
    private static final String ADMIN_PHONE = "+17132490142";
    private static final String PASSWORD = "Plantain-Jollof-2026";

    /** Staff sign-in costs an SMS code (rate limited), so the admin token is shared by the tests in this class. */
    private static String adminAccessToken;

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;
    private final FederatedLoginService federated;

    @Autowired
    IdentityFlowIT(JdbcClient jdbc, FederatedLoginService federated) {
        this.jdbc = jdbc;
        this.federated = federated;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void servesDiscoveryAndJwks() {
        BrowserSession s = new BrowserSession(base());
        JsonNode discovery = BrowserSession.json(s.get(base() + "/.well-known/openid-configuration").body());
        assertThat(discovery.get("issuer").asString()).isEqualTo("http://localhost:8100");
        assertThat(discovery.get("jwks_uri").asString()).isEqualTo("http://localhost:8100/oauth2/jwks");
        assertThat(discovery.get("code_challenge_methods_supported").toString()).contains("S256");

        HttpResponse<String> jwks = s.get(base() + "/oauth2/jwks");
        assertThat(jwks.statusCode()).isEqualTo(200);
        JsonNode key = BrowserSession.json(jwks.body()).get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("RSA");
        assertThat(key.get("kid").asString()).isEqualTo("identity-1");
        assertThat(key.has("d")).isFalse();
    }

    @Test
    void clientCredentialsTokensAreServicePrincipals() {
        Tokens t = new BrowserSession(base()).clientCredentials("seller-service", "local-seller-secret",
                "orgs:manage");
        assertThat(t.status()).isEqualTo(200);
        assertThat(t.accessClaims().get("pt").asString()).isEqualTo("SERVICE");
        assertThat(t.accessClaims().get("perms").toString()).contains("orgs:manage");
        assertThat(t.accessClaims().get("sub").asString()).isEqualTo("seller-service");
        long ttl = t.accessClaims().get("exp").asLong() - t.accessClaims().get("iat").asLong();
        assertThat(ttl).isEqualTo(300);
    }

    @Test
    void signUpVerifyEmailThenSignInWithPkceAndRotateRefreshTokens() {
        BrowserSession s = new BrowserSession(base());
        String email = "ada." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, signUp(email, "sell"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String userId = BrowserSession.json(created.body()).get("userId").asString();

        // Signing up again before confirming restarts the pending registration, still subject to the resend limit.
        HttpResponse<String> again = s.api("POST", "/v1/accounts", null, signUp(email, "shop"));
        assertThat(again.statusCode()).isEqualTo(429);

        // Unverified accounts cannot sign in.
        assertThat(rejected(() -> new BrowserSession(base()).signIn(STOREFRONT, STOREFRONT_REDIRECT, email,
                PASSWORD))).isTrue();

        HttpResponse<String> wrong = s.api("POST", "/v1/accounts/verification", null,
                "{\"email\":\"" + email + "\",\"code\":\"000000\"}");
        assertThat(wrong.statusCode()).isEqualTo(422);
        verifyEmail(s, email);
        assertThat(s.api("POST", "/v1/accounts", null, signUp(email, "shop")).statusCode()).isEqualTo(409);

        AuthorizeResult auth = s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        Tokens tokens = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT, auth);
        assertThat(tokens.status()).as(tokens.body()).isEqualTo(200);
        JsonNode claims = tokens.accessClaims();
        assertThat(claims.get("sub").asString()).isEqualTo(userId);
        assertThat(claims.get("pt").asString()).isEqualTo("CUSTOMER");
        assertThat(strings(claims.get("roles"))).containsExactly("account.customer", "account.seller_applicant");
        assertThat(strings(claims.get("perms"))).containsExactly("applications:submit");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300);
        assertThat(BrowserSession.claims(tokens.idToken()).get("email").asString()).isEqualTo(email);
        assertThat(tokens.refreshToken()).isNotBlank();

        JsonNode me = BrowserSession.json(s.api("GET", "/v1/me", tokens.accessToken(), null).body());
        assertThat(me.get("landing").asString()).isEqualTo("seller-onboarding");
        assertThat(me.get("phone").asString()).isEqualTo("+17132490142");

        Tokens refreshed = s.refreshPublic(STOREFRONT, tokens.refreshToken());
        assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
        assertThat(refreshed.refreshToken()).isNotEqualTo(tokens.refreshToken());
        assertThat(s.refreshPublic(STOREFRONT, tokens.refreshToken()).status()).isEqualTo(400);

        JsonNode sessions = BrowserSession.json(s.api("GET", "/v1/me/sessions", refreshed.accessToken(), null).body());
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(sessions.get(0).get("current").asBoolean()).isTrue();
        assertThat(sessions.get(0).get("clientId").asString()).isEqualTo(STOREFRONT);
    }

    @Test
    void existingCustomerStartsSellingAndLandsByOrgStatus() {
        BrowserSession s = new BrowserSession(base());
        String email = "kemi." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email, "shop");
        Tokens customer = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(landing(s, customer.accessToken())).isEqualTo("shop");

        HttpResponse<String> selling = s.api("POST", "/v1/me/roles/seller-applicant", customer.accessToken(), null);
        assertThat(BrowserSession.json(selling.body()).get("landing").asString()).isEqualTo("seller-onboarding");

        String service = new BrowserSession(base()).clientCredentials("seller-service", "local-seller-secret",
                "orgs:manage").accessToken();
        String orgId = UUID.randomUUID().toString();
        assertThat(s.api("PUT", "/v1/orgs/" + orgId, service,
                "{\"kind\":\"SELLER\",\"name\":\"Kemi's Pantry\",\"status\":\"PENDING\"}").statusCode()).isEqualTo(200);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + userId, service,
                "{\"roleKey\":\"seller.owner\"}").statusCode()).isEqualTo(204);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + userId, customer.accessToken(),
                "{\"roleKey\":\"seller.owner\"}").statusCode()).isEqualTo(403);

        Tokens pending = s.refreshPublic(STOREFRONT, customer.refreshToken());
        assertThat(pending.status()).as(pending.body()).isEqualTo(200);
        assertThat(pending.accessClaims().get("org").asString()).isEqualTo(orgId);
        assertThat(strings(pending.accessClaims().get("perms"))).doesNotContain("catalog:write");
        assertThat(landing(s, pending.accessToken())).isEqualTo("seller-onboarding");

        s.api("PUT", "/v1/orgs/" + orgId, service, "{\"kind\":\"SELLER\",\"name\":\"Kemi's Pantry\",\"status\":\"APPROVED\"}");
        Tokens approved = s.refreshPublic(STOREFRONT, pending.refreshToken());
        assertThat(approved.accessClaims().get("pt").asString()).isEqualTo("SELLER_MEMBER");
        assertThat(strings(approved.accessClaims().get("perms"))).contains("catalog:write", "orders:read");
        assertThat(landing(s, approved.accessToken())).isEqualTo("seller-center");
    }

    @Test
    void staffSignInNeedsSmsCodeAndRoleChangesApplyOnNextToken() {
        BrowserSession user = new BrowserSession(base());
        String email = "tunde." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(user, email, "shop");
        Tokens userTokens = user.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                user.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(strings(userTokens.accessClaims().get("perms"))).doesNotContain("delivery:operate");

        String admin = adminToken();
        assertThat(user.api("GET", "/v1/admin/users", userTokens.accessToken(), null).statusCode()).isEqualTo(403);

        HttpResponse<String> granted = user.api("PUT", "/v1/admin/users/" + userId + "/roles/platform.driver?reason=hired",
                admin, null);
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
        assertThat(BrowserSession.json(granted.body()).get("driverAppAccess").asBoolean()).isTrue();

        Tokens driver = user.refreshPublic(STOREFRONT, userTokens.refreshToken());
        assertThat(strings(driver.accessClaims().get("perms"))).contains("delivery:operate");
        assertThat(driver.accessClaims().get("pt").asString()).isEqualTo("DRIVER");
        assertThat(landing(user, driver.accessToken())).isEqualTo("driver-app");

        assertThat(user.api("DELETE", "/v1/admin/users/" + userId + "/roles/platform.driver", admin, null)
                .statusCode()).isEqualTo(200);
        Tokens revoked = user.refreshPublic(STOREFRONT, driver.refreshToken());
        assertThat(strings(revoked.accessClaims().get("perms"))).doesNotContain("delivery:operate");
        assertThat(revoked.accessClaims().get("pt").asString()).isEqualTo("CUSTOMER");

        user.api("PUT", "/v1/admin/users/" + userId + "/permissions/delivery:operate", admin, null);
        Tokens direct = user.refreshPublic(STOREFRONT, revoked.refreshToken());
        assertThat(strings(direct.accessClaims().get("perms"))).contains("delivery:operate");

        JsonNode log = BrowserSession.json(user.api("GET", "/v1/admin/access-log?userId=" + userId, admin, null).body());
        List<String> actions = new ArrayList<>();
        log.get("items").forEach(n -> actions.add(n.get("action").asString()));
        assertThat(actions).contains("permission.granted", "role.revoked", "role.granted");

        HttpResponse<String> disabled = user.api("POST", "/v1/admin/users/" + userId + "/disable", admin,
                "{\"reason\":\"left the company\"}");
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(user.refreshPublic(STOREFRONT, direct.refreshToken()).status()).isEqualTo(400);
        long events = jdbc.sql("select count(*) from platform_outbox where aggregate_id = :id and type in "
                + "('identity.role.granted','identity.role.revoked','identity.permission.granted','identity.user.disabled')")
                .param("id", userId).query(Long.class).single();
        assertThat(events).isEqualTo(4);
    }

    @Test
    void adminManagesModuleMatrixAndIdentityProviders() {
        BrowserSession s = new BrowserSession(base());
        String admin = adminToken();
        JsonNode matrix = BrowserSession.json(s.api("GET", "/v1/admin/module-access?portal=platform", admin, null)
                .body());
        assertThat(matrix.get("modules").size()).isEqualTo(27);
        HttpResponse<String> set = s.api("PUT", "/v1/admin/module-access/platform/dispatch/platform.marketing", admin,
                "{\"level\":\"view\"}");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        JsonNode roles = BrowserSession.json(s.api("GET", "/v1/admin/roles?portal=seller", admin, null).body());
        assertThat(roles.size()).isEqualTo(7);

        // Google is configured (test credentials) and enabled by default; Apple/Facebook are not configured.
        JsonNode publicProviders = BrowserSession.json(s.api("GET", "/v1/identity-providers", null, null).body());
        assertThat(publicProviders.size()).isEqualTo(1);
        assertThat(publicProviders.get(0).get("id").asString()).isEqualTo("google");
        assertThat(s.get(base() + "/login").body()).contains("/oauth2/authorization/google");
        HttpResponse<String> toGoogle = s.get(base() + "/oauth2/authorization/google");
        assertThat(toGoogle.statusCode()).isEqualTo(302);
        assertThat(toGoogle.headers().firstValue("Location").orElseThrow())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth").contains("client_id=it-google-client");
        HttpResponse<String> enableApple = s.api("PUT", "/v1/admin/identity-providers/apple", admin,
                "{\"enabled\":true}");
        assertThat(enableApple.statusCode()).isEqualTo(422);

        assertThat(s.api("PUT", "/v1/admin/identity-providers/google", admin, "{\"enabled\":false}").statusCode())
                .isEqualTo(200);
        assertThat(BrowserSession.json(s.api("GET", "/v1/identity-providers", null, null).body()).size()).isZero();
        BrowserSession other = new BrowserSession(base());
        assertThat(other.get(base() + "/oauth2/authorization/google").headers().firstValue("Location").orElse(""))
                .doesNotContain("accounts.google.com");
        assertThat(other.get(base() + "/login").body()).doesNotContain("/oauth2/authorization/google");
        assertThat(s.api("PUT", "/v1/admin/identity-providers/google", admin, "{\"enabled\":true}").statusCode())
                .isEqualTo(200);
    }

    @Test
    void socialSignInCreatesVerifiedAccountsAndLinksByVerifiedEmail() {
        String email = "bisi." + UUID.randomUUID() + "@gmail.com";
        UUID created = federated.signIn(new FederatedProfile("google", "g-" + UUID.randomUUID(), email, true, "Bisi",
                "Adeyemi"), Optional.empty());
        JsonNode user = BrowserSession.json(new BrowserSession(base()).api("GET", "/v1/admin/users/" + created,
                adminToken(), null).body());
        assertThat(user.get("emailVerified").asBoolean()).isTrue();
        assertThat(user.get("name").asString()).isEqualTo("Bisi Adeyemi");
        assertThat(strings(user.get("roles"))).containsExactly("account.customer");
        long codes = jdbc.sql("select count(*) from platform_outbox where type = 'identity.verification.requested' "
                + "and payload->'recipient'->>'email' = :e").param("e", email).query(Long.class).single();
        assertThat(codes).isZero();

        // Same Google subject signs in again → same account.
        String subject = jdbc.sql("select subject from federated_identity where user_id = :u").param("u", created)
                .query(String.class).single();
        assertThat(federated.signIn(new FederatedProfile("google", subject, email, true, "Bisi", "Adeyemi"),
                Optional.empty())).isEqualTo(created);

        // An existing password account is linked when the provider verified the same email.
        BrowserSession s = new BrowserSession(base());
        String existingEmail = "funmi." + UUID.randomUUID() + "@example.com";
        String existingId = registerAndVerify(s, existingEmail, "shop");
        assertThat(federated.signIn(new FederatedProfile("google", "g-" + UUID.randomUUID(), existingEmail, true,
                "Funmi", "Ade"), Optional.empty()).toString()).isEqualTo(existingId);

        // Unverified provider emails are refused.
        assertThat(rejectedDomain(() -> federated.signIn(new FederatedProfile("google", "g-x", "x@example.com",
                false, "X", "Y"), Optional.empty()))).isTrue();
    }

    @Test
    void totpEnrolmentRequiresSecondFactorUntilBrowserIsTrusted() {
        BrowserSession s = new BrowserSession(base());
        String email = "ama." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, email, "provide");
        Tokens t = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        JsonNode setup = BrowserSession.json(s.api("POST", "/v1/me/mfa/totp", t.accessToken(), null).body());
        String secret = setup.get("secret").asString();
        assertThat(setup.get("otpauthUri").asString()).startsWith("otpauth://totp/AfroTransact:");
        DefaultTotpAuthenticator totp = new DefaultTotpAuthenticator();
        long step = Instant.now().getEpochSecond() / 30;
        JsonNode codes = BrowserSession.json(s.api("POST", "/v1/me/mfa/totp/confirm", t.accessToken(),
                "{\"code\":\"" + totp.codeAt(secret, step) + "\"}").body());
        assertThat(codes.get("recoveryCodes").size()).isEqualTo(10);

        BrowserSession laptop = new BrowserSession(base());
        AuthorizeResult first = laptop.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        String code = laptop.completeMfa("RECOVERY_CODE", codes.get("recoveryCodes").get(0).asString(), true);
        Tokens laptopTokens = laptop.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                new AuthorizeResult(code, false, first.verifier()));
        assertThat(laptopTokens.status()).isEqualTo(200);

        // Same browser later (new login session, trusted-browser cookie kept): no second factor.
        laptop.cookies().getCookieStore().getCookies().stream().filter(c -> "SESSION".equals(c.getName()))
                .toList().forEach(c -> laptop.cookies().getCookieStore().remove(null, c));
        AuthorizeResult again = laptop.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD);
        assertThat(again.mfaRequired()).isFalse();

        JsonNode status = BrowserSession.json(s.api("GET", "/v1/me/mfa", t.accessToken(), null).body());
        assertThat(status.get("totp").asBoolean()).isTrue();
        assertThat(status.get("recoveryCodesLeft").asInt()).isEqualTo(9);
        assertThat(status.get("mayDisable").asBoolean()).isTrue();
    }

    @Test
    void passkeyCeremoniesAreServedByTheAuthorizationServer() {
        BrowserSession anonymous = new BrowserSession(base());
        assertThat(anonymous.get(base() + "/login/webauthn.js").statusCode()).isEqualTo(200);
        HttpResponse<String> authOptions = anonymous.postJson("/webauthn/authenticate/options", "{}",
                anonymous.csrfToken());
        assertThat(authOptions.statusCode()).as(authOptions.body()).isEqualTo(200);
        assertThat(BrowserSession.json(authOptions.body()).get("rpId").asString()).isEqualTo("localhost");

        BrowserSession s = new BrowserSession(base());
        String email = "chidi." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email, "shop");
        Tokens t = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        HttpResponse<String> regOptions = s.postJson("/webauthn/register/options", "{}", s.csrfToken());
        assertThat(regOptions.statusCode()).as(regOptions.body()).isEqualTo(200);
        JsonNode options = BrowserSession.json(regOptions.body());
        assertThat(options.get("rp").get("id").asString()).isEqualTo("localhost");
        assertThat(options.get("user").get("name").asString()).isEqualTo(userId);
        assertThat(options.get("challenge").asString()).isNotBlank();

        HttpResponse<String> passkeys = s.api("GET", "/v1/me/passkeys", t.accessToken(), null);
        assertThat(passkeys.statusCode()).isEqualTo(200);
        assertThat(BrowserSession.json(passkeys.body()).size()).isZero();
        JsonNode linked = BrowserSession.json(s.api("GET", "/v1/me/linked-accounts", t.accessToken(), null).body());
        assertThat(linked.get("hasPassword").asBoolean()).isTrue();
        assertThat(s.api("DELETE", "/v1/me/linked-accounts/google", t.accessToken(), null).statusCode())
                .isEqualTo(404);
        assertThat(s.api("PUT", "/v1/me/password", t.accessToken(),
                "{\"currentPassword\":\"wrong\",\"newPassword\":\"Suya-Spice-Mix-2027\","
                        + "\"confirmPassword\":\"Suya-Spice-Mix-2027\"}").statusCode()).isEqualTo(422);
        assertThat(s.api("PUT", "/v1/me/password", t.accessToken(),
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Suya-Spice-Mix-2027\","
                        + "\"confirmPassword\":\"Suya-Spice-Mix-2027\"}").statusCode()).isEqualTo(204);
    }

    @Test
    void resendIsRateLimitedAndStaffDomainsCannotSelfRegister() {
        BrowserSession s = new BrowserSession(base());
        String email = "femi." + UUID.randomUUID() + "@example.com";
        assertThat(s.api("POST", "/v1/accounts", null, signUp(email, "shop")).statusCode()).isEqualTo(201);
        HttpResponse<String> resend = s.api("POST", "/v1/accounts/verification/resend", null,
                "{\"email\":\"" + email + "\"}");
        assertThat(resend.statusCode()).isEqualTo(429);
        assertThat(resend.headers().firstValue("Retry-After")).isPresent();
        assertThat(BrowserSession.json(resend.body()).get("code").asString()).isEqualTo("identity.resend-too-soon");

        HttpResponse<String> staff = s.api("POST", "/v1/accounts", null, signUp("new.hire@afrotransact.com", "shop"));
        assertThat(staff.statusCode()).isEqualTo(422);
        HttpResponse<String> weak = s.api("POST", "/v1/accounts", null, signUp(email, "shop")
                .replace(PASSWORD, "femi-password-1"));
        assertThat(weak.statusCode()).isEqualTo(422);
        assertThat(BrowserSession.json(weak.body()).get("fieldErrors").has("password")).isTrue();
    }

    private synchronized String adminToken() {
        if (adminAccessToken != null) {
            return adminAccessToken;
        }
        BrowserSession admin = new BrowserSession(base());
        AuthorizeResult first = admin.signIn(STAFF_CONSOLE, STAFF_REDIRECT, ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(first.mfaRequired()).isTrue();
        admin.requestSmsCode();
        String code = admin.completeMfa("SMS", latestCode(ADMIN_PHONE), false);
        Tokens t = admin.exchangePublic(STAFF_CONSOLE, STAFF_REDIRECT, new AuthorizeResult(code, false,
                first.verifier()));
        assertThat(t.accessClaims().get("pt").asString()).isEqualTo("STAFF");
        assertThat(strings(t.accessClaims().get("perms"))).contains("access:admin", "delivery:operate");
        adminAccessToken = t.accessToken();
        return adminAccessToken;
    }

    private String registerAndVerify(BrowserSession s, String email, String intent) {
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, signUp(email, intent));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        verifyEmail(s, email);
        return BrowserSession.json(created.body()).get("userId").asString();
    }

    private void verifyEmail(BrowserSession s, String email) {
        HttpResponse<String> verified = s.api("POST", "/v1/accounts/verification", null,
                "{\"email\":\"" + email + "\",\"code\":\"" + latestCode(email) + "\"}");
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(204);
    }

    private String latestCode(String destination) {
        return jdbc.sql("""
                select payload->>'code' from platform_outbox
                where type = 'identity.verification.requested' and aggregate_type = 'identity'
                  and coalesce(payload->'recipient'->>'email', payload->'recipient'->>'phone') = :d
                  and payload->>'channel' = case when :d like '+%' then 'sms' else 'email' end
                order by occurred_at desc limit 1
                """).param("d", destination).query(String.class).single();
    }

    private static String landing(BrowserSession s, String token) {
        return BrowserSession.json(s.api("GET", "/v1/me", token, null).body()).get("landing").asString();
    }

    private static String signUp(String email, String intent) {
        return """
                {"firstName":"Adaeze","lastName":"Okafor","email":"%s","phone":"(713) 249-0142","phoneRegion":"US",
                 "password":"%s","confirmPassword":"%s","termsAccepted":true,"smsConsent":false,"intent":"%s"}
                """.formatted(email, PASSWORD, PASSWORD, intent);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asString()));
        }
        return out;
    }

    private static boolean rejectedDomain(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IdentityRuleViolation expected) {
            return true;
        }
    }

    private static boolean rejected(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }
}
