package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.DefaultTotpAuthenticator;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.identity.testsupport.TestPasskeyAuthenticator;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The first-party sign-in API as gateway-bff uses it (real HTTP, real PostgreSQL): password → MFA → login ticket →
 * /oauth2/authorize?login_ticket → code → /oauth2/token; passkey assertion → ticket → tokens; per-account and per-IP
 * lockouts (also on the hosted form) with audit rows and events; push devices, notification preferences and the
 * service lookups notifications-service calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox",
    "at.identity.sign-in-protection.ip.max-failures=8"})
@ActiveProfiles("local")
@Testcontainers
class FirstPartySignInIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String BFF_REDIRECT = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    /** A public PKCE client with refresh tokens (the customer app); web apps sign in through gateway-bff. */
    private static final String STOREFRONT = "customer-app";
    private static final String STOREFRONT_REDIRECT = "http://localhost:8081/auth/callback";
    private static final String PASSWORD = "Plantain-Jollof-2026";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;
    private final PublicKeyCredentialUserEntityRepository passkeyUsers;
    private final UserCredentialRepository passkeyCredentials;

    @Autowired
    FirstPartySignInIT(JdbcClient jdbc, PublicKeyCredentialUserEntityRepository passkeyUsers,
                       UserCredentialRepository passkeyCredentials) {
        this.jdbc = jdbc;
        this.passkeyUsers = passkeyUsers;
        this.passkeyCredentials = passkeyCredentials;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void passwordThenTotpThenTicketThenTokensWithUsualClaimsAndRefresh() {
        BrowserSession s = new BrowserSession(base());
        String email = "first.party." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email);
        String secret = enrolTotp(s, email);
        String bff = bffToken();
        String ip = "198.51.100.1";

        HttpResponse<String> step = s.api("POST", "/v1/auth/password", bff, password(email, PASSWORD, ip, null));
        assertThat(step.statusCode()).as(step.body()).isEqualTo(200);
        JsonNode mfa = BrowserSession.json(step.body());
        assertThat(mfa.get("status").asString()).isEqualTo("MFA_REQUIRED");
        assertThat(mfa.has("loginTicket")).isFalse();
        assertThat(mfa.get("factors").get("totp").asBoolean()).isTrue();
        assertThat(mfa.get("factors").get("recoveryCode").asBoolean()).isTrue();
        String challengeId = mfa.get("challengeId").asString();

        HttpResponse<String> wrong = s.api("POST", "/v1/auth/mfa/verify", bff, verify(challengeId, "TOTP", "000000",
                false, ip));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(wrong.body()).get("code").asString()).isEqualTo("identity.invalid-code");
        assertThat(BrowserSession.json(wrong.body()).get("attemptsLeft").asInt()).isEqualTo(4);

        String code = new DefaultTotpAuthenticator().codeAt(secret, Instant.now().getEpochSecond() / 30 + 1);
        HttpResponse<String> ok = s.api("POST", "/v1/auth/mfa/verify", bff, verify(challengeId, "TOTP", code, true,
                ip));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        JsonNode done = BrowserSession.json(ok.body());
        assertThat(done.get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(done.get("userId").asString()).isEqualTo(userId);
        String ticket = done.get("loginTicket").asString();
        String trustedDevice = done.get("trustedDeviceToken").asString();
        assertThat(ticket).hasSizeGreaterThan(40);
        assertThat(s.api("POST", "/v1/auth/mfa/verify", bff, verify(challengeId, "TOTP", code, false, ip))
                .statusCode()).as("challenge is single use").isEqualTo(401);

        Tokens tokens = redeem(ticket);
        assertThat(tokens.status()).as(tokens.body()).isEqualTo(200);
        JsonNode claims = tokens.accessClaims();
        assertThat(claims.get("sub").asString()).isEqualTo(userId);
        assertThat(claims.get("pt").asString()).isEqualTo("CUSTOMER");
        assertThat(claims.get("roles").toString()).contains("account.customer");
        assertThat(claims.get("email").asString()).isEqualTo(email);
        assertThat(claims.get("aud").toString()).contains(BFF);
        assertThat(BrowserSession.claims(tokens.idToken()).get("nonce").asString()).isEqualTo("n-1");
        assertThat(tokens.refreshToken()).isNotBlank();
        Tokens refreshed = new BrowserSession(base()).refreshConfidential(BFF, BFF_SECRET, tokens.refreshToken());
        assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
        assertThat(refreshed.accessClaims().get("sub").asString()).isEqualTo(userId);

        // The ticket is single use.
        HttpResponse<String> replay = authorize(ticket, BrowserSession.randomVerifier());
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(replay.body()).contains("invalid_grant");

        // The trusted-device token skips the second factor next time.
        JsonNode trusted = BrowserSession.json(s.api("POST", "/v1/auth/password", bff,
                password(email, PASSWORD, ip, trustedDevice)).body());
        assertThat(trusted.get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(redeem(trusted.get("loginTicket").asString()).status()).isEqualTo(200);

        List<String> outcomes = jdbc.sql("select method || ':' || outcome from sign_in_audit where user_id = :u "
                + "order by id").param("u", UUID.fromString(userId)).query(String.class).list();
        assertThat(outcomes).contains("PASSWORD:MFA_REQUIRED", "MFA_TOTP:FAILED", "MFA_TOTP:SUCCEEDED",
                "LOGIN_TICKET:TICKET_REDEEMED", "PASSWORD:SUCCEEDED");
    }

    @Test
    void onlyGatewayBffMayUseTheApi() {
        BrowserSession s = new BrowserSession(base());
        String body = password("nobody@example.com", "x", "198.51.100.2", null);
        assertThat(s.api("POST", "/v1/auth/password", null, body).statusCode()).isEqualTo(401);
        String notifications = s.clientCredentials("notifications-service", "local-notifications-service-secret",
                "users:read").accessToken();
        assertThat(s.api("POST", "/v1/auth/password", notifications, body).statusCode()).isEqualTo(403);
        String email = "user.token." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, email);
        String user = userToken(s, email);
        assertThat(s.api("POST", "/v1/auth/password", user, body).statusCode()).isEqualTo(403);
        HttpResponse<String> noIp = s.api("POST", "/v1/auth/password", bffToken(),
                "{\"email\":\"a@example.com\",\"password\":\"x\"}");
        assertThat(noIp.statusCode()).as("the end user's IP is required").isEqualTo(400);
    }

    @Test
    void passkeyAssertionSignsInWithoutSecondFactor() {
        BrowserSession s = new BrowserSession(base());
        String email = "passkey." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email);
        enrolTotp(s, email);
        TestPasskeyAuthenticator passkey = new TestPasskeyAuthenticator("localhost");
        passkey.register(passkeyUsers, passkeyCredentials, userId);
        String bff = bffToken();
        String client = "\"clientIp\":\"198.51.100.3\",\"userAgent\":\"Safari\"";

        HttpResponse<String> options = s.api("POST", "/v1/auth/passkey/options", bff, "{" + client + "}");
        assertThat(options.statusCode()).as(options.body()).isEqualTo(200);
        JsonNode o = BrowserSession.json(options.body());
        assertThat(o.get("publicKey").get("rpId").asString()).isEqualTo("localhost");
        assertThat(o.get("publicKey").get("userVerification").asString()).isEqualTo("required");
        String challenge = o.get("publicKey").get("challenge").asString();
        String challengeId = o.get("challengeId").asString();

        String credential = passkey.assertion(challenge, "http://localhost:3000");
        HttpResponse<String> verified = s.api("POST", "/v1/auth/passkey/verify", bff,
                "{\"challengeId\":\"" + challengeId + "\",\"credential\":" + credential + "," + client + "}");
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
        JsonNode step = BrowserSession.json(verified.body());
        assertThat(step.get("status").asString()).as("passkeys skip TOTP").isEqualTo("AUTHENTICATED");
        Tokens tokens = redeem(step.get("loginTicket").asString());
        assertThat(tokens.status()).as(tokens.body()).isEqualTo(200);
        assertThat(tokens.accessClaims().get("sub").asString()).isEqualTo(userId);

        HttpResponse<String> replay = s.api("POST", "/v1/auth/passkey/verify", bff,
                "{\"challengeId\":\"" + challengeId + "\",\"credential\":" + passkey.assertion(challenge,
                        "http://localhost:3000") + "," + client + "}");
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(BrowserSession.json(replay.body()).get("code").asString()).isEqualTo("identity.challenge-invalid");
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountEverywhereAndAreAudited() {
        BrowserSession s = new BrowserSession(base());
        String email = "locked." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email);
        String bff = bffToken();
        String ip = "198.51.100.4";
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = s.api("POST", "/v1/auth/password", bff, password(email, "wrong-" + i, ip, null));
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(BrowserSession.json(r.body()).get("code").asString()).isEqualTo("identity.invalid-credentials");
        }
        HttpResponse<String> locked = s.api("POST", "/v1/auth/password", bff, password(email, PASSWORD,
                "198.51.100.5", null));
        assertThat(locked.statusCode()).as("even the right password, from another IP").isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(BrowserSession.json(locked.body()).get("code").asString()).isEqualTo("identity.sign-in-locked");
        assertThatThrownBy(() -> new BrowserSession(base()).signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD))
                .as("the hosted form is locked too").hasMessageContaining("error=locked");

        String event = jdbc.sql("select payload->>'scope' from platform_outbox where type = 'identity.sign-in.locked' "
                + "and aggregate_id = :u").param("u", userId).query(String.class).single();
        assertThat(event).isEqualTo("ACCOUNT");
        assertThat(jdbc.sql("select count(*) from sign_in_audit where user_id = :u and outcome = 'LOCKED'")
                .param("u", UUID.fromString(userId)).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from sign_in_audit where email = :e and outcome = 'BLOCKED'")
                .param("e", email).query(Integer.class).single()).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("delete from sign_in_audit where email = :e").param("e", email).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void manyFailuresFromOneIpLockThatIp() {
        BrowserSession s = new BrowserSession(base());
        String bff = bffToken();
        String ip = "198.51.100.6";
        for (int i = 0; i < 8; i++) {
            assertThat(s.api("POST", "/v1/auth/password", bff, password("spray" + i + "@example.com", "guess", ip,
                    null)).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = s.api("POST", "/v1/auth/password", bff, password("another@example.com",
                "guess", ip, null));
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(s.api("POST", "/v1/auth/passkey/options", bff, "{\"clientIp\":\"" + ip + "\"}").statusCode())
                .isEqualTo(429);
        assertThat(s.api("POST", "/v1/auth/password", bff, password("another@example.com", "guess",
                "198.51.100.7", null)).statusCode()).as("other addresses are unaffected").isEqualTo(401);
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'identity.sign-in.locked' "
                + "and aggregate_id = :k").param("k", "ip:" + ip).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void devicesPreferencesAndServiceLookupsForNotifications() {
        BrowserSession s = new BrowserSession(base());
        String email = "contact." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email);
        String token = userToken(s, email);

        HttpResponse<String> ios = s.api("POST", "/v1/me/devices", token,
                "{\"platform\":\"ios\",\"token\":\"apns-device-token-0001\",\"app\":\"customer\","
                        + "\"deviceName\":\"Ada's iPhone\"}");
        assertThat(ios.statusCode()).as(ios.body()).isEqualTo(201);
        assertThat(BrowserSession.json(ios.body()).get("provider").asString()).isEqualTo("apns");
        assertThat(ios.body()).doesNotContain("apns-device-token-0001");
        assertThat(s.api("POST", "/v1/me/devices", token, "{\"platform\":\"android\",\"token\":\"fcm-token-2\","
                + "\"app\":\"customer\"}").statusCode()).isEqualTo(201);
        assertThat(s.api("POST", "/v1/me/devices", token, "{\"platform\":\"android\",\"token\":\"fcm-token-3\","
                + "\"app\":\"driver\"}").statusCode()).as("drivers only").isEqualTo(403);
        assertThat(BrowserSession.json(s.api("GET", "/v1/me/devices", token, null).body()).size()).isEqualTo(2);

        JsonNode defaults = BrowserSession.json(s.api("GET", "/v1/me/notification-preferences", token, null).body());
        assertThat(defaults.get("push").asBoolean()).isTrue();
        assertThat(defaults.get("marketingOptIn").asBoolean()).isFalse();
        HttpResponse<String> put = s.api("PUT", "/v1/me/notification-preferences", token,
                "{\"email\":true,\"sms\":true,\"push\":false,\"marketingOptIn\":true,\"locale\":\"fr-ca\","
                        + "\"timeZone\":\"America/Toronto\"}");
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(BrowserSession.json(put.body()).get("locale").asString()).isEqualTo("fr-CA");
        assertThat(s.api("PUT", "/v1/me/notification-preferences", token,
                "{\"email\":true,\"sms\":true,\"push\":true,\"marketingOptIn\":false,\"timeZone\":\"Mars/Base\"}")
                .statusCode()).isEqualTo(422);

        String notifications = s.clientCredentials("notifications-service", "local-notifications-service-secret",
                "users:read").accessToken();
        HttpResponse<String> contact = s.api("GET", "/v1/internal/users/" + userId + "/contact", notifications, null);
        assertThat(contact.statusCode()).as(contact.body()).isEqualTo(200);
        JsonNode c = BrowserSession.json(contact.body());
        assertThat(c.get("userId").asString()).isEqualTo(userId);
        assertThat(c.get("firstName").asString()).isEqualTo("Adaeze");
        assertThat(c.get("email").asString()).isEqualTo(email);
        assertThat(c.get("phone").asString()).isEqualTo("+17132490142");
        assertThat(c.get("phoneVerified").asBoolean()).isFalse();
        assertThat(c.get("locale").asString()).isEqualTo("fr-CA");
        assertThat(c.get("timeZone").asString()).isEqualTo("America/Toronto");
        assertThat(c.get("preferences").get("push").asBoolean()).isFalse();
        assertThat(c.get("preferences").get("email").asBoolean()).isTrue();
        assertThat(c.get("marketingOptIn").asBoolean()).isTrue();
        assertThat(c.get("pushTokens").size()).isEqualTo(2);
        List<String> platforms = new ArrayList<>();
        c.get("pushTokens").forEach(t -> platforms.add(t.get("token").asString() + "@" + t.get("platform").asString()));
        assertThat(platforms).containsExactlyInAnyOrder("apns-device-token-0001@apns", "fcm-token-2@fcm");

        assertThat(s.api("DELETE", "/v1/me/devices", token, "{\"token\":\"apns-device-token-0001\"}").statusCode())
                .isEqualTo(204);
        assertThat(s.api("DELETE", "/v1/me/devices", token, "{\"token\":\"apns-device-token-0001\"}").statusCode())
                .as("idempotent").isEqualTo(204);
        assertThat(BrowserSession.json(s.api("GET", "/v1/internal/users/" + userId + "/contact", notifications, null)
                .body()).get("pushTokens").size()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from platform_outbox where aggregate_id = :u and type in "
                + "('identity.device.registered', 'identity.device.unregistered', "
                + "'identity.notification-preferences.updated')").param("u", userId).query(Integer.class).single())
                .isEqualTo(4);

        assertThat(s.api("GET", "/v1/internal/users/" + UUID.randomUUID() + "/contact", notifications, null)
                .statusCode()).isEqualTo(404);
        assertThat(s.api("GET", "/v1/internal/users/not-a-uuid/contact", notifications, null).statusCode())
                .isEqualTo(404);
        assertThat(s.api("GET", "/v1/internal/users/" + userId + "/contact", token, null).statusCode())
                .as("users cannot look up contacts").isEqualTo(403);

        String seller = s.clientCredentials("seller-service", "local-seller-secret", "orgs:manage").accessToken();
        String orgId = "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId, seller, "{\"kind\":\"SELLER\",\"name\":\"Mama Put\","
                + "\"status\":\"APPROVED\"}").statusCode()).isEqualTo(200);
        assertThat(s.api("PUT", "/v1/orgs/" + orgId + "/members/" + userId, seller, "{\"roleKey\":\"seller.owner\"}")
                .statusCode()).isEqualTo(204);
        JsonNode owners = BrowserSession.json(s.api("GET", "/v1/internal/orgs/" + orgId + "/owners", notifications,
                null).body());
        assertThat(owners.get("orgId").asString()).isEqualTo(orgId);
        assertThat(owners.get("ownerUserIds").get(0).asString()).isEqualTo(userId);
        assertThat(s.api("GET", "/v1/internal/orgs/org_unknown0000000000000/owners", notifications, null)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void trustOpsServiceIsAServicePrincipalAndSellersEnforceIsSeeded() {
        Tokens t = new BrowserSession(base()).clientCredentials("trust-ops-service", "local-trust-ops-secret",
                "catalog:moderate payments:service compliance:hold offers:write ai:triage users:read");
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        assertThat(t.accessClaims().get("pt").asString()).isEqualTo("SERVICE");
        assertThat(t.accessClaims().get("perms").toString()).contains("users:read", "offers:write", "ai:triage");
        List<String> grants = jdbc.sql("select role_key from role_permission where permission_key = 'sellers:enforce'")
                .query(String.class).list();
        assertThat(grants).containsExactlyInAnyOrder("platform.super_admin", "platform.trust_safety",
                "platform.operations_manager");
        assertThat(jdbc.sql("select role_key from role_permission where permission_key = 'delivery:dispatch:read'")
                .query(String.class).list()).containsExactly("platform.customer_support");
        Tokens fulfilment = new BrowserSession(base()).clientCredentials("fulfilment-service",
                "local-fulfilment-service-secret", "delivery:relay");
        assertThat(fulfilment.status()).as(fulfilment.body()).isEqualTo(200);
        assertThat(new BrowserSession(base()).clientCredentials("fulfilment-service",
                "local-fulfilment-service-secret", "payments:service").status()).isEqualTo(400);
    }

    @Test
    void taxReturnsServiceClientAndTaxAndShippingPermissions() {
        Tokens taxReturns = new BrowserSession(base()).clientCredentials("tax-returns-service",
                "local-tax-returns-service-secret", "payments:service inventory:adjust shipping:label users:read");
        assertThat(taxReturns.status()).as(taxReturns.body()).isEqualTo(200);
        assertThat(taxReturns.accessClaims().get("pt").asString()).isEqualTo("SERVICE");
        assertThat(taxReturns.accessClaims().get("perms").toString()).contains("payments:service",
                "inventory:adjust", "shipping:label", "users:read");
        assertThat(new BrowserSession(base()).clientCredentials("tax-returns-service",
                "local-tax-returns-service-secret", "orgs:manage").status()).isEqualTo(400);
        Tokens orders = new BrowserSession(base()).clientCredentials("orders-service", "local-orders-service-secret",
                "tax:quote");
        assertThat(orders.status()).as(orders.body()).isEqualTo(200);
        assertThat(orders.accessClaims().get("perms").toString()).contains("tax:quote");

        assertThat(grants("tax:quote")).containsExactly("platform.super_admin");
        assertThat(grants("tax:read")).containsExactlyInAnyOrder("platform.super_admin", "platform.finance",
                "platform.auditor");
        assertThat(grants("tax:configure")).containsExactlyInAnyOrder("platform.super_admin", "platform.finance");
        assertThat(grants("shipping:label")).contains("platform.super_admin", "platform.fulfilment_ops",
                "seller.owner", "seller.admin", "seller.fulfilment").doesNotContain("seller.catalog");
        assertThat(grants("shipping:rate")).contains("seller.fulfilment", "platform.dispatcher");
        assertThat(grants("shipping:void")).contains("seller.owner", "platform.operations_manager");
        assertThat(grants("shipping:admin")).containsExactlyInAnyOrderElementsOf(grants("shipping:manage"));
    }

    private List<String> grants(String permission) {
        return jdbc.sql("select role_key from role_permission where permission_key = :p").param("p", permission)
                .query(String.class).list();
    }

    private Tokens redeem(String ticket) {
        String verifier = BrowserSession.randomVerifier();
        HttpResponse<String> r = authorize(ticket, verifier);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        String location = BrowserSession.location(r);
        assertThat(location).startsWith(BFF_REDIRECT).contains("state=st-bff");
        return new BrowserSession(base()).exchangeConfidential(BFF, BFF_SECRET, BFF_REDIRECT,
                BrowserSession.code(location), verifier);
    }

    /** What the BFF does server-side: no cookies, redirects not followed. */
    private HttpResponse<String> authorize(String ticket, String verifier) {
        return new BrowserSession(base()).get(base() + "/oauth2/authorize?response_type=code&client_id=" + BFF
                + "&redirect_uri=" + enc(BFF_REDIRECT) + "&scope=" + enc("openid profile email phone offline_access")
                + "&state=st-bff&nonce=n-1&code_challenge=" + BrowserSession.challenge(verifier)
                + "&code_challenge_method=S256&login_ticket=" + enc(ticket));
    }

    private String bffToken() {
        Tokens t = new BrowserSession(base()).clientCredentials(BFF, BFF_SECRET, "auth:first-party");
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t.accessToken();
    }

    private String enrolTotp(BrowserSession s, String email) {
        String token = userToken(s, email);
        String secret = BrowserSession.json(s.api("POST", "/v1/me/mfa/totp", token, null).body()).get("secret")
                .asString();
        String code = new DefaultTotpAuthenticator().codeAt(secret, Instant.now().getEpochSecond() / 30);
        assertThat(s.api("POST", "/v1/me/mfa/totp/confirm", token, "{\"code\":\"" + code + "\"}").statusCode())
                .isEqualTo(200);
        return secret;
    }

    private String userToken(BrowserSession s, String email) {
        Tokens t = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t.accessToken();
    }

    private String registerAndVerify(BrowserSession s, String email) {
        HttpResponse<String> created = s.api("POST", "/v1/accounts", null, """
                {"firstName":"Adaeze","lastName":"Okafor","email":"%s","phone":"(713) 249-0142","phoneRegion":"US",
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
        return BrowserSession.json(created.body()).get("userId").asString();
    }

    private static String password(String email, String password, String ip, String trustedDevice) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"clientIp\":\"" + ip
                + "\",\"userAgent\":\"Mozilla/5.0 (IT)\""
                + (trustedDevice == null ? "" : ",\"trustedDeviceToken\":\"" + trustedDevice + "\"") + "}";
    }

    private static String verify(String challengeId, String factor, String code, boolean trust, String ip) {
        return "{\"challengeId\":\"" + challengeId + "\",\"factor\":\"" + factor + "\",\"code\":\"" + code
                + "\",\"trustDevice\":" + trust + ",\"clientIp\":\"" + ip + "\"}";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
