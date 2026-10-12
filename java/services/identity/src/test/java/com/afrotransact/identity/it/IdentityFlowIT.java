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
import com.afrotransact.identity.application.SellerOrgChange;
import com.afrotransact.identity.application.SellerOrgSyncService;
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
    "at.consumers.enabled=false",
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

    /** A public PKCE client with refresh tokens (the customer app); web apps sign in through gateway-bff. */
    private static final String STOREFRONT = "customer-app";
    private static final String STOREFRONT_REDIRECT = "http://localhost:8081/auth/callback";
    /** Public client without the phone scope (the driver app), standing in for a staff-facing app. */
    private static final String STAFF_CONSOLE = "driver-app";
    private static final String STAFF_REDIRECT = "http://localhost:8082/auth/callback";
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
    private final SellerOrgSyncService sellerOrgs;

    @Autowired
    IdentityFlowIT(JdbcClient jdbc, FederatedLoginService federated, SellerOrgSyncService sellerOrgs) {
        this.jdbc = jdbc;
        this.federated = federated;
        this.sellerOrgs = sellerOrgs;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void bothOrganisationsAndContentMarketCodesAreMirrored() {
        String orgId = "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        assertThat(sellerOrgs.approved(new SellerOrgChange(UUID.randomUUID(), orgId, "BOTH", "PROVIDER",
                "Ama Studio LLC", "Ama's", "uk", "APPROVED", List.of(), null, Instant.now()))).isTrue();
        assertThat(jdbc.sql("select kind || ' ' || market from organization where id = :o").param("o", orgId)
                .query(String.class).single()).isEqualTo("BOTH GB");
        // A country that is not a market code leaves the stored market as it was.
        assertThat(sellerOrgs.updated(new SellerOrgChange(UUID.randomUUID(), orgId, "BOTH", "PROVIDER", null,
                "Ama's", "Texas", "APPROVED", List.of(), null, Instant.now()))).isTrue();
        assertThat(jdbc.sql("select market from organization where id = :o").param("o", orgId)
                .query(String.class).single()).isEqualTo("GB");
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
        // S298: the seller service withholds plan fees from earnings at payments.
        Tokens planFees = new BrowserSession(base()).clientCredentials("seller-service", "local-seller-secret",
                "payments:plan-fees");
        assertThat(planFees.status()).as(planFees.body()).isEqualTo(200);
        assertThat(strings(planFees.accessClaims().get("perms"))).containsExactly("payments:plan-fees");
        long ttl = t.accessClaims().get("exp").asLong() - t.accessClaims().get("iat").asLong();
        assertThat(ttl).isEqualTo(300);

        Tokens search = new BrowserSession(base()).clientCredentials("search-service", "local-search-service-secret",
                "ai:use");
        assertThat(search.status()).as(search.body()).isEqualTo(200);
        assertThat(strings(search.accessClaims().get("perms"))).containsExactly("ai:use");
        Tokens retail = new BrowserSession(base()).clientCredentials("retail-service", "local-retail-service-secret",
                "inventory:receive");
        assertThat(retail.status()).as(retail.body()).isEqualTo(200);
        assertThat(strings(retail.accessClaims().get("perms"))).containsExactly("inventory:receive");
        assertThat(new BrowserSession(base()).clientCredentials("retail-service", "local-retail-service-secret",
                "inventory:adjust").status()).as("retail-service no longer holds inventory:adjust").isEqualTo(400);

        Tokens inventory = new BrowserSession(base()).clientCredentials("inventory-service",
                "local-inventory-service-secret", "catalog:read catalog:write shipping:label");
        assertThat(inventory.status()).as(inventory.body()).isEqualTo(200);
        assertThat(inventory.accessClaims().get("pt").asString()).isEqualTo("SERVICE");
        assertThat(strings(inventory.accessClaims().get("perms")))
                .containsExactlyInAnyOrder("catalog:read", "catalog:write", "shipping:label");
        Tokens orders = new BrowserSession(base()).clientCredentials("orders-service", "local-orders-service-secret",
                "offers:read");
        assertThat(orders.status()).as(orders.body()).isEqualTo(200);
    }

    @Test
    void mcpClientAndAiPermissionsAreProvisioned() {
        String mcp = jdbc.sql("select client_authentication_methods || '|' || authorization_grant_types || '|' || "
                + "scopes || '|' || client_settings from oauth2_registered_client where client_id = 'mcp-client'")
                .query(String.class).single();
        assertThat(mcp).contains("none", "authorization_code", "mcp:shopper", "mcp:seller", "mcp:staff", "openid")
                .contains("require-proof-key\":true");
        List<String> grants = jdbc.sql("select role_key || '>' || permission_key from role_permission "
                + "where permission_key in ('ai:use', 'ai:triage', 'mcp:shopper', 'mcp:seller', 'mcp:staff')")
                .query(String.class).list();
        assertThat(grants).contains("seller.owner>ai:use", "provider.owner>ai:use", "platform.super_admin>ai:use",
                "platform.trust_safety>ai:triage", "account.customer>mcp:shopper", "seller.owner>mcp:seller",
                "platform.operations_manager>mcp:staff").doesNotContain("platform.driver>mcp:staff");
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
        assertThat(strings(claims.get("perms"))).containsExactly("applications:submit", "mcp:shopper");
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
        String orgId = "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        assertThat(s.api("PUT", "/v1/orgs/not%20an%20id", service,
                "{\"kind\":\"SELLER\",\"name\":\"Bad\",\"status\":\"PENDING\"}").statusCode()).isEqualTo(422);
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
    void sellerOrgApprovedGrantsOwnerRoleAndOrgClaimOnNextToken() {
        BrowserSession s = new BrowserSession(base());
        String email = "bisi." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email, "sell");
        Tokens before = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(before.accessClaims().has("org")).isFalse();
        // Profile claims for prefill are on every access token.
        assertThat(before.accessClaims().get("given_name").asString()).isEqualTo("Adaeze");
        assertThat(before.accessClaims().get("family_name").asString()).isEqualTo("Okafor");
        assertThat(before.accessClaims().get("email").asString()).isEqualTo(email);
        assertThat(before.accessClaims().get("email_verified").asBoolean()).isTrue();
        assertThat(before.accessClaims().get("phone_number").asString()).isEqualTo("+17132490142");

        String orgId = "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        UUID eventId = UUID.randomUUID();
        SellerOrgChange approved = new SellerOrgChange(eventId, orgId, "SELLER", "SELLER", "Bisi Foods LLC",
                "Bisi's Pantry", "us", "APPROVED", List.of(userId), userId, Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(sellerOrgs.approved(approved)).isTrue();
        assertThat(sellerOrgs.approved(approved)).as("redelivery is a no-op").isFalse();
        assertThat(jdbc.sql("select count(*) from org_membership where org_id = :o and role_key = 'seller.owner'")
                .param("o", orgId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select market from organization where id = :o").param("o", orgId)
                .query(String.class).single()).isEqualTo("US");

        Tokens after = s.refreshPublic(STOREFRONT, before.refreshToken());
        assertThat(after.status()).as(after.body()).isEqualTo(200);
        assertThat(after.accessClaims().get("org").asString()).isEqualTo(orgId);
        assertThat(after.accessClaims().get("pt").asString()).isEqualTo("SELLER_MEMBER");
        assertThat(strings(after.accessClaims().get("roles"))).contains("seller.owner");
        assertThat(strings(after.accessClaims().get("perms"))).contains("catalog:write");
        assertThat(landing(s, after.accessToken())).isEqualTo("seller-center");

        assertThat(sellerOrgs.updated(new SellerOrgChange(UUID.randomUUID(), orgId, "SELLER", null, "Bisi Foods LLC",
                "Bisi's Kitchen", "US", "APPROVED", List.of(userId), null, Instant.now()))).isTrue();
        assertThat(jdbc.sql("select name from organization where id = :o").param("o", orgId)
                .query(String.class).single()).isEqualTo("Bisi's Kitchen");
        assertThat(sellerOrgs.updated(new SellerOrgChange(UUID.randomUUID(), orgId, "SELLER", null, null, null, null,
                "SUSPENDED", List.of(), null, Instant.now()))).isTrue();
        Tokens suspended = s.refreshPublic(STOREFRONT, after.refreshToken());
        assertThat(suspended.accessClaims().get("org").asString()).isEqualTo(orgId);
        assertThat(strings(suspended.accessClaims().get("perms"))).doesNotContain("catalog:write");
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
    void staffRolesScopedToMarketsPublishTheMarketOnRoleEvents() {
        BrowserSession user = new BrowserSession(base());
        String userId = registerAndVerify(user, "ama." + UUID.randomUUID() + "@example.com", "shop");
        String admin = adminToken();
        String role = "/v1/admin/users/" + userId + "/roles/platform.customer_support";

        HttpResponse<String> gb = user.api("PUT", role + "?market=uk&reason=GB%20desk", admin, null);
        assertThat(gb.statusCode()).as(gb.body()).isEqualTo(200);
        HttpResponse<String> us = user.api("PUT", role + "?market=US", admin, null);
        assertThat(us.statusCode()).as(us.body()).isEqualTo(200);
        JsonNode view = BrowserSession.json(us.body());
        assertThat(strings(view.get("roleMarkets").get("platform.customer_support"))).containsExactly("GB", "US");
        assertThat(strings(view.get("roles"))).contains("platform.customer_support");
        assertThat(roleEventMarkets(userId, "identity.role.granted")).containsExactly("GB", "US");

        // A role is either platform-wide or market-scoped; Super Admin and account roles never are.
        assertThat(problemCode(user.api("PUT", role, admin, null))).isEqualTo("identity.role-market-scoped");
        assertThat(problemCode(user.api("PUT", "/v1/admin/users/" + userId + "/roles/platform.super_admin?market=GB",
                admin, null))).isEqualTo("identity.role-not-market-scoped");
        assertThat(problemCode(user.api("PUT", role + "?market=G1", admin, null))).isEqualTo("identity.invalid-market");

        // Removing one market keeps the role for the other; removing the last removes the role.
        assertThat(user.api("DELETE", role + "?market=GB", admin, null).statusCode()).isEqualTo(200);
        HttpResponse<String> last = user.api("DELETE", role + "?market=US", admin, null);
        assertThat(strings(BrowserSession.json(last.body()).get("roles"))).doesNotContain("platform.customer_support");
        assertThat(roleEventMarkets(userId, "identity.role.revoked")).containsExactly("GB", "US");

        // Platform-wide again: no market on the event, and the role can't then be scoped without removing it first.
        assertThat(user.api("PUT", role, admin, null).statusCode()).isEqualTo(200);
        assertThat(problemCode(user.api("PUT", role + "?market=GB", admin, null)))
                .isEqualTo("identity.role-platform-wide");
        assertThat(jdbc.sql("select count(*) from platform_outbox where aggregate_id = :id and type = "
                + "'identity.role.granted' and payload->>'market' is null").param("id", userId).query(Long.class)
                .single()).isEqualTo(1);
    }

    private List<String> roleEventMarkets(String userId, String type) {
        return jdbc.sql("select payload->>'market' from platform_outbox where aggregate_id = :id and type = :t "
                        + "and payload->>'market' is not null order by occurred_at, payload->>'market'")
                .param("id", userId).param("t", type).query(String.class).list();
    }

    private static String problemCode(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isGreaterThanOrEqualTo(400);
        return BrowserSession.json(response.body()).get("code").asString();
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
        assertThat(status.get("mayRemoveTotp").asBoolean()).isTrue();

        // The trusted browser is listed and can be forgotten: the next sign-in asks for the second factor again.
        JsonNode trusted = BrowserSession.json(s.api("GET", "/v1/me/trusted-browsers", t.accessToken(), null).body());
        assertThat(trusted.size()).isEqualTo(1);
        assertThat(trusted.get(0).get("expiresAt").asString()).isNotBlank();
        assertThat(s.api("DELETE", "/v1/me/trusted-browsers/" + UUID.randomUUID(), t.accessToken(), null)
                .statusCode()).isEqualTo(404);
        assertThat(s.api("DELETE", "/v1/me/trusted-browsers/" + trusted.get(0).get("id").asString(),
                t.accessToken(), null).statusCode()).isEqualTo(204);
        assertThat(BrowserSession.json(s.api("GET", "/v1/me/trusted-browsers", t.accessToken(), null).body()).size())
                .isZero();
        laptop.cookies().getCookieStore().getCookies().stream().filter(c -> "SESSION".equals(c.getName()))
                .toList().forEach(c -> laptop.cookies().getCookieStore().remove(null, c));
        assertThat(laptop.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD).mfaRequired()).isTrue();

        // Recent sign-in activity of this account, newest first, capped at 50 rows.
        JsonNode activity = BrowserSession.json(s.api("GET", "/v1/me/sign-in-activity?limit=2", t.accessToken(), null)
                .body());
        assertThat(activity.size()).isEqualTo(2);
        List<String> outcomes = new ArrayList<>();
        activity.forEach(a -> outcomes.add(a.get("outcome").asString()));
        assertThat(outcomes).contains("SUCCEEDED");
        assertThat(Instant.parse(activity.get(0).get("at").asString()))
                .isAfterOrEqualTo(Instant.parse(activity.get(1).get("at").asString()));
        JsonNode capped = BrowserSession.json(s.api("GET", "/v1/me/sign-in-activity?limit=500", t.accessToken(), null)
                .body());
        assertThat(capped.size()).isBetween(3, 50);
    }

    @Test
    void preferencesAndWorkProfileKeepTheNotificationSwitches() {
        BrowserSession s = new BrowserSession(base());
        String email = "kemi." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, email, "sell");
        String token = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD)).accessToken();
        assertThat(s.api("PUT", "/v1/me/notification-preferences", token,
                "{\"email\":true,\"sms\":false,\"push\":true,\"marketingOptIn\":true}").statusCode()).isEqualTo(200);

        JsonNode defaults = BrowserSession.json(s.api("GET", "/v1/me/preferences", token, null).body());
        assertThat(defaults.get("tableDensity").asString()).isEqualTo("COMFORTABLE");
        assertThat(defaults.get("dateFormat").asString()).isEqualTo("AUTO");

        HttpResponse<String> saved = s.api("PUT", "/v1/me/preferences", token, """
                {"locale":"fr-ca","timeZone":"America/Toronto","dateFormat":"DMY","numberFormat":"SPACE_COMMA",
                 "tableDensity":"COMPACT"}""");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        JsonNode prefs = BrowserSession.json(s.api("GET", "/v1/me/preferences", token, null).body());
        assertThat(prefs.get("locale").asString()).isEqualTo("fr-CA");
        assertThat(prefs.get("timeZone").asString()).isEqualTo("America/Toronto");
        assertThat(prefs.get("numberFormat").asString()).isEqualTo("SPACE_COMMA");
        assertThat(prefs.get("tableDensity").asString()).isEqualTo("COMPACT");
        JsonNode switches = BrowserSession.json(s.api("GET", "/v1/me/notification-preferences", token, null).body());
        assertThat(switches.get("sms").asBoolean()).isFalse();
        assertThat(switches.get("marketingOptIn").asBoolean()).isTrue();

        HttpResponse<String> badZone = s.api("PUT", "/v1/me/preferences", token,
                "{\"timeZone\":\"Mars/Olympus\",\"dateFormat\":\"AUTO\",\"numberFormat\":\"AUTO\",\"tableDensity\":\"COMPACT\"}");
        assertThat(badZone.statusCode()).isEqualTo(422);
        assertThat(BrowserSession.json(badZone.body()).get("fieldErrors").has("timeZone")).isTrue();
        assertThat(s.api("PUT", "/v1/me/preferences", token, "{\"dateFormat\":\"SOMETIMES\"}").statusCode())
                .isEqualTo(400);

        JsonNode noTitle = BrowserSession.json(s.api("GET", "/v1/me/work-profile", token, null).body()).get("jobTitle");
        assertThat(noTitle == null || noTitle.isNull()).isTrue();
        JsonNode work = BrowserSession.json(s.api("PUT", "/v1/me/work-profile", token,
                "{\"jobTitle\":\"  Catalogue lead \"}").body());
        assertThat(work.get("jobTitle").asString()).isEqualTo("Catalogue lead");
    }

    @Test
    void staffSwitchTwoStepMethodsButCannotTurnItOff() {
        BrowserSession user = new BrowserSession(base());
        String email = "yemi." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(user, email, "shop");
        Tokens customer = user.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                user.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(user.api("PUT", "/v1/admin/users/" + userId + "/roles/platform.customer_support?reason=hired",
                adminToken(), null).statusCode()).isEqualTo(200);
        String token = user.refreshPublic(STOREFRONT, customer.refreshToken()).accessToken();

        JsonNode status = BrowserSession.json(user.api("GET", "/v1/me/mfa", token, null).body());
        assertThat(status.get("required").asBoolean()).isTrue();
        assertThat(status.get("mayDisable").asBoolean()).isFalse();

        String secret = BrowserSession.json(user.api("POST", "/v1/me/mfa/totp", token, null).body()).get("secret")
                .asString();
        String code = new DefaultTotpAuthenticator().codeAt(secret, Instant.now().getEpochSecond() / 30);
        assertThat(user.api("POST", "/v1/me/mfa/totp/confirm", token, "{\"code\":\"" + code + "\"}")
                .statusCode()).isEqualTo(200);
        status = BrowserSession.json(user.api("GET", "/v1/me/mfa", token, null).body());
        assertThat(status.get("totp").asBoolean()).isTrue();
        assertThat(status.get("mayRemoveTotp").asBoolean()).isFalse();
        HttpResponse<String> refused = user.api("DELETE", "/v1/me/mfa/totp", token, null);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(problemCode(refused)).isEqualTo("identity.staff-mfa-required");

        // Switch to SMS: turn SMS codes on, then the authenticator app may go, but never the last factor.
        assertThat(user.api("POST", "/v1/me/mfa/sms", token, null).statusCode()).isEqualTo(202);
        assertThat(user.api("POST", "/v1/me/mfa/sms/confirm", token,
                "{\"code\":\"" + latestCode(ADMIN_PHONE) + "\"}").statusCode()).isEqualTo(200);
        status = BrowserSession.json(user.api("GET", "/v1/me/mfa", token, null).body());
        assertThat(status.get("mayRemoveTotp").asBoolean()).isTrue();
        assertThat(status.get("mayRemoveSms").asBoolean()).isTrue();
        assertThat(user.api("DELETE", "/v1/me/mfa/totp", token, null).statusCode()).isEqualTo(204);
        assertThat(user.api("DELETE", "/v1/me/mfa/sms", token, null).statusCode()).isEqualTo(403);
        status = BrowserSession.json(user.api("GET", "/v1/me/mfa", token, null).body());
        assertThat(status.get("sms").asBoolean()).isTrue();
        assertThat(status.get("totp").asBoolean()).isFalse();
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
    void customersKeepAnAddressBookWithOneDefault() {
        BrowserSession s = new BrowserSession(base());
        String email = "book." + UUID.randomUUID() + "@example.com";
        registerAndVerify(s, email, "shop");
        String token = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD)).accessToken();
        String home = """
                {"label":"Home","fullName":"Adaeze Okafor","phone":"(713) 249-0142","phoneRegion":"US",
                 "line1":"123 Main St","line2":"Apt 4B","city":"Houston","state":"TX","postalCode":"77002",
                 "latitude":29.7589,"longitude":-95.3677,"instructions":"Ring twice","verified":true}
                """;
        HttpResponse<String> first = s.api("POST", "/v1/me/addresses", token, home);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(BrowserSession.json(first.body()).get("isDefault").asBoolean()).isTrue();
        assertThat(BrowserSession.json(first.body()).get("phone").asString()).isEqualTo("+17132490142");

        HttpResponse<String> second = s.api("POST", "/v1/me/addresses", token,
                home.replace("Home", "Work").replace("123 Main St", "9800 Bissonnet St").replace("77002", "77036"));
        String secondId = BrowserSession.json(second.body()).get("id").asString();
        assertThat(BrowserSession.json(second.body()).get("isDefault").asBoolean()).isFalse();
        assertThat(s.api("POST", "/v1/me/addresses/" + secondId + "/default", token, null).statusCode()).isEqualTo(200);

        JsonNode list = BrowserSession.json(s.api("GET", "/v1/me/addresses", token, null).body());
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("id").asString()).isEqualTo(secondId);
        assertThat(list.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(list.get(1).get("isDefault").asBoolean()).isFalse();

        assertThat(s.api("DELETE", "/v1/me/addresses/" + secondId, token, null).statusCode()).isEqualTo(204);
        JsonNode after = BrowserSession.json(s.api("GET", "/v1/me/addresses", token, null).body());
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.get(0).get("isDefault").asBoolean()).isTrue();

        HttpResponse<String> invalid = s.api("POST", "/v1/me/addresses", token,
                home.replace("\"TX\"", "\"ZZ\"").replace("77002", "770"));
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("state").contains("postalCode");
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
