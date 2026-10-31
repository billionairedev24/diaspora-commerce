package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.AdminSession;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.it.BrowserSession.Tokens;
import com.afrotransact.identity.port.SessionAdminStore;
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
 * S291 session device column on PostgreSQL: the User-Agent the BFF reports with a first-party sign-in travels on the
 * login ticket and is recorded with the authorization (cut to 255 characters, the IP only as a SHA-256 hash), a
 * refresh keeps it, a hosted sign-in records the request's own User-Agent, and the admin session list returns it.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs the test on that PostgreSQL instead of a container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
class SessionDeviceIT {

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

    private static final String BFF = "gateway-bff";
    private static final String BFF_SECRET = "local-bff-secret";
    private static final String BFF_REDIRECT = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    private static final String STOREFRONT = "customer-app";
    private static final String STOREFRONT_REDIRECT = "http://localhost:8081/auth/callback";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final String CHROME_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;
    private final SessionAdminStore sessions;

    @Autowired
    SessionDeviceIT(JdbcClient jdbc, SessionAdminStore sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void bffSignInRecordsTheEndUsersBrowserAndTheAdminListReturnsIt() {
        BrowserSession s = new BrowserSession(base());
        String email = "device." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email);
        String bff = bffToken();

        // an over-long User-Agent is cut to 255 characters
        String longAgent = CHROME_MAC + " ".repeat(10) + "x".repeat(300);
        HttpResponse<String> step = s.api("POST", "/v1/auth/password", bff, "{\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\",\"clientIp\":\"203.0.113.9\",\"userAgent\":\"" + longAgent
                + "\"}");
        assertThat(step.statusCode()).as(step.body()).isEqualTo(200);
        String ticket = BrowserSession.json(step.body()).get("loginTicket").asString();

        // the BFF redeems the ticket server-side: its own request carries Java's User-Agent, not the user's
        Tokens tokens = redeem(ticket);
        assertThat(tokens.status()).as(tokens.body()).isEqualTo(200);

        AdminSession row = sessions.search(userId, null, null, Instant.now(), 10, 0).items().getFirst();
        assertThat(row.userAgent()).hasSize(255).startsWith("Mozilla/5.0 (Macintosh").contains("Chrome/124");
        assertThat(row.clientId()).isEqualTo(BFF);
        assertThat(jdbc.sql("""
                select ip_hash from session_device where authorization_id = :id
                """).param("id", row.id()).query(String.class).single())
                .isEqualTo(SecretHashing.sha256Hex("203.0.113.9")).doesNotContain("203.0.113.9");
        assertThat(sessions.find(row.id()).orElseThrow().userAgent()).isEqualTo(row.userAgent());

        // the ticket is single use and the recorded device is not replaced by a refresh made by the BFF
        if (tokens.refreshToken() != null) {
            Tokens refreshed = new BrowserSession(base()).refreshConfidential(BFF, BFF_SECRET, tokens.refreshToken());
            assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
            assertThat(sessions.find(row.id()).orElseThrow().userAgent()).isEqualTo(row.userAgent());
        }

        // revoking the sign-in removes its device row with it
        assertThat(sessions.revoke(row.id())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from session_device where authorization_id = :id")
                .param("id", row.id()).query(Long.class).single()).isZero();
    }

    @Test
    void hostedSignInRecordsTheRequestsOwnUserAgent() {
        BrowserSession s = new BrowserSession(base());
        String email = "hosted." + UUID.randomUUID() + "@example.com";
        String userId = registerAndVerify(s, email);
        Tokens t = s.exchangePublic(STOREFRONT, STOREFRONT_REDIRECT,
                s.signIn(STOREFRONT, STOREFRONT_REDIRECT, email, PASSWORD));
        assertThat(t.status()).as(t.body()).isEqualTo(200);

        AdminSession row = sessions.search(userId, null, null, Instant.now(), 10, 0).items().getFirst();
        assertThat(row.userAgent()).isNotBlank().hasSizeLessThanOrEqualTo(255);
        assertThat(row.clientId()).isEqualTo(STOREFRONT);
    }

    private Tokens redeem(String ticket) {
        String verifier = BrowserSession.randomVerifier();
        HttpResponse<String> r = new BrowserSession(base()).get(base()
                + "/oauth2/authorize?response_type=code&client_id=" + BFF + "&redirect_uri=" + enc(BFF_REDIRECT)
                + "&scope=" + enc("openid profile email phone offline_access") + "&state=st-bff&nonce=n-1&code_challenge="
                + BrowserSession.challenge(verifier) + "&code_challenge_method=S256&login_ticket=" + enc(ticket));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
        return new BrowserSession(base()).exchangeConfidential(BFF, BFF_SECRET, BFF_REDIRECT,
                BrowserSession.code(BrowserSession.location(r)), verifier);
    }

    private String bffToken() {
        Tokens t = new BrowserSession(base()).clientCredentials(BFF, BFF_SECRET, "auth:first-party");
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
        JsonNode body = BrowserSession.json(created.body());
        return body.get("userId").asString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
