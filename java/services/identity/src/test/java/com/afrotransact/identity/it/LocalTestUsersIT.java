package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.application.SellerOrgChange;
import com.afrotransact.identity.application.SellerOrgSyncService;
import com.afrotransact.identity.application.TestPersonas;
import com.afrotransact.identity.application.TestUserReport;
import com.afrotransact.identity.application.TestUserService;
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
 * S276 on the local profile, real PostgreSQL and HTTP: the test users exist at start-up; customers, sellers and the
 * driver sign in with email + password only (no second step); staff still get the two-step challenge (policy kept);
 * a seller organisation that arrives later (make seed) gets its personas linked; re-running is idempotent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
@Testcontainers
class LocalTestUsersIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String BFF_REDIRECT = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    private static final String PASSWORD = "Test1234!Local";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;
    private final TestUserService testUsers;
    private final SellerOrgSyncService sellerOrgs;

    @Autowired
    LocalTestUsersIT(JdbcClient jdbc, TestUserService testUsers, SellerOrgSyncService sellerOrgs) {
        this.jdbc = jdbc;
        this.testUsers = testUsers;
        this.sellerOrgs = sellerOrgs;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void everyPersonaExistsAtStartUpVerifiedWithItsRole() {
        assertThat(jdbc.sql("select count(*) from user_account where email like '%@afrotransact.test' "
                + "and email_verified and enabled").query(Integer.class).single()).isEqualTo(TestPersonas.ALL.size());
        assertThat(jdbc.sql("select r.role_key from user_role r join user_account u on u.id = r.user_id "
                + "where u.email = 'staff.finance@afrotransact.test'").query(String.class).list())
                .containsExactly("platform.finance");
        assertThat(jdbc.sql("select a.postal_code || ' ' || a.is_default from customer_address a join user_account u "
                + "on u.id = a.user_id where u.email = 'customer.houston@afrotransact.test'").query(String.class)
                .single()).isEqualTo("77042 true");
        // AfroTransact Retail is created by identity's own migration: its operator is linked straight away.
        assertThat(jdbc.sql("select m.role_key from org_membership m join user_account u on u.id = m.user_id "
                + "where u.email = 'retail.operator@afrotransact.test' and m.org_id = 'org_afrotransact_retail'")
                .query(String.class).single()).isEqualTo("seller.admin");
    }

    @Test
    void customersSellersAndTheDriverSignInWithEmailAndPasswordOnly() {
        BrowserSession s = new BrowserSession(base());
        String bff = bffToken();

        JsonNode customer = signIn(s, bff, "customer.houston@afrotransact.test");
        assertThat(customer.get("status").asString()).isEqualTo("AUTHENTICATED");
        Tokens tokens = redeem(customer.get("loginTicket").asString());
        assertThat(tokens.accessClaims().get("pt").asString()).isEqualTo("CUSTOMER");

        JsonNode retail = signIn(s, bff, "retail.operator@afrotransact.test");
        assertThat(retail.get("status").asString()).isEqualTo("AUTHENTICATED");
        JsonNode retailClaims = redeem(retail.get("loginTicket").asString()).accessClaims();
        assertThat(retailClaims.get("pt").asString()).isEqualTo("SELLER_MEMBER");
        assertThat(retailClaims.get("org").asString()).isEqualTo("org_afrotransact_retail");

        JsonNode driver = signIn(s, bff, "driver@afrotransact.test");
        assertThat(driver.get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(redeem(driver.get("loginTicket").asString()).accessClaims().get("pt").asString())
                .isEqualTo("DRIVER");
    }

    @Test
    void staffKeepTheirTwoStepPolicyWithATextedCode() {
        JsonNode staff = signIn(new BrowserSession(base()), bffToken(), "staff.support@afrotransact.test");
        assertThat(staff.get("status").asString()).isEqualTo("MFA_REQUIRED");
        assertThat(staff.has("loginTicket")).isFalse();
        assertThat(staff.get("factors").get("sms").asBoolean()).isTrue();
        assertThat(staff.get("factors").get("smsHint").asString()).endsWith("0105");
    }

    @Test
    void sellerOrganisationCreatedLaterGetsItsOwnerAndStaffLinkedAndReRunsAreIdempotent() {
        assertThat(memberRole("seller.owner@afrotransact.test", "org_mama_put_pantry")).isEmpty();

        sellerOrgs.approved(new SellerOrgChange(UUID.randomUUID(), "org_mama_put_pantry", "SELLER", null,
                "Mama Put Pantry LLC", "Mama Put Pantry", "US", "APPROVED", List.of(), null, Instant.now()));

        assertThat(memberRole("seller.owner@afrotransact.test", "org_mama_put_pantry")).containsExactly("seller.owner");
        assertThat(memberRole("seller.staff@afrotransact.test", "org_mama_put_pantry"))
                .containsExactly("seller.catalog");
        JsonNode owner = signIn(new BrowserSession(base()), bffToken(), "seller.owner@afrotransact.test");
        JsonNode claims = redeem(owner.get("loginTicket").asString()).accessClaims();
        assertThat(claims.get("org").asString()).isEqualTo("org_mama_put_pantry");
        assertThat(claims.get("roles").toString()).contains("seller.owner");

        jdbc.sql("update user_account set password_hash = null where email = 'customer.lubbock@afrotransact.test'")
                .update();
        TestUserReport again = testUsers.ensureAll();
        assertThat(again.created()).isZero();
        assertThat(again.repaired()).isEqualTo(1);
        assertThat(again.membershipsLinked()).isZero();
        assertThat(again.addressesAdded()).isZero();
        assertThat(signIn(new BrowserSession(base()), bffToken(), "customer.lubbock@afrotransact.test")
                .get("status").asString()).isEqualTo("AUTHENTICATED");
    }

    private List<String> memberRole(String email, String orgId) {
        return jdbc.sql("select m.role_key from org_membership m join user_account u on u.id = m.user_id "
                + "where u.email = :e and m.org_id = :o").param("e", email).param("o", orgId)
                .query(String.class).list();
    }

    private JsonNode signIn(BrowserSession s, String bff, String email) {
        HttpResponse<String> r = s.api("POST", "/v1/auth/password", bff, "{\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\",\"clientIp\":\"198.51.100.76\",\"userAgent\":\"IT\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return BrowserSession.json(r.body());
    }

    private String bffToken() {
        Tokens t = new BrowserSession(base()).clientCredentials(BFF, BFF_SECRET, "auth:first-party");
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t.accessToken();
    }

    private Tokens redeem(String ticket) {
        String verifier = BrowserSession.randomVerifier();
        HttpResponse<String> r = new BrowserSession(base()).get(base() + "/oauth2/authorize?response_type=code"
                + "&client_id=" + BFF + "&redirect_uri=" + enc(BFF_REDIRECT) + "&scope=" + enc("openid profile email")
                + "&state=st&nonce=n&code_challenge=" + BrowserSession.challenge(verifier)
                + "&code_challenge_method=S256&login_ticket=" + enc(ticket));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        Tokens t = new BrowserSession(base()).exchangeConfidential(BFF, BFF_SECRET, BFF_REDIRECT,
                BrowserSession.code(BrowserSession.location(r)), verifier);
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
