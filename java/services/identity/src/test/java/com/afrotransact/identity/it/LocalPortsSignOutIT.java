package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
 * The local stack when :3001 is taken: tools/dev/local.sh runs Seller Center on :3010 and passes SELLER_CENTER_URL to
 * identity, so its callback, its post-logout landing and its branding all follow the variable (LAN mode works the same
 * way with the Wi-Fi address).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox",
    "SELLER_CENTER_URL=http://localhost:3010"})
@ActiveProfiles("local")
@Testcontainers
class LocalPortsSignOutIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String BFF = "gateway-bff";
    private static final String SELLER = "http://localhost:3010";
    private static final String CALLBACK = SELLER + "/api/bff/v1/auth/oidc/callback";
    private static final String SIGNED_OUT = SELLER + "/api/bff/v1/auth/signed-out/seller-center";
    private static final String PASSWORD = "Plantain-Jollof-2026";

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    LocalPortsSignOutIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void sellerCenterOnAnotherPortSignsInAndOutWithItsOwnBranding() {
        String email = verifiedAccount();
        BrowserSession s = new BrowserSession(base());
        String verifier = BrowserSession.randomVerifier();
        String login = BrowserSession.location(s.get(authorizeUrl(CALLBACK, verifier, "")));
        HttpResponse<String> page = s.get(absolute(login));
        assertThat(page.body()).contains("Sign in to Seller Center").doesNotContain("Staff console");
        assertThat(s.get(base() + "/login/forgot").body()).contains("data-app=\"seller\"");
        HttpResponse<String> posted = s.form("/login", Map.of("username", email, "password", PASSWORD,
                "_csrf", BrowserSession.csrfOf(page.body())));
        String issued = BrowserSession.location(s.get(absolute(BrowserSession.location(posted))));
        assertThat(issued).startsWith(CALLBACK + "?code=");
        Tokens t = s.exchangeConfidential(BFF, "local-bff-secret", CALLBACK, BrowserSession.code(issued), verifier);

        HttpResponse<String> logout = s.get(base() + "/connect/logout?id_token_hint=" + enc(t.idToken())
                + "&client_id=" + BFF + "&post_logout_redirect_uri=" + enc(SIGNED_OUT) + "&state=%2Forders");
        assertThat(BrowserSession.location(logout)).isEqualTo(SIGNED_OUT + "?state=%2Forders");
        // The sign-in Seller Center starts next is its own, saying the person signed out.
        BrowserSession next = new BrowserSession(base());
        String again = BrowserSession.location(next.get(authorizeUrl(CALLBACK, BrowserSession.randomVerifier(),
                "&prompt=login&notice=signed_out")));
        assertThat(next.get(absolute(again)).body()).contains("Sign in to Seller Center", "You’ve been signed out.");
        // The default port is not registered any more for this stack.
        assertThat(new BrowserSession(base()).get(authorizeUrl("http://localhost:3001/api/bff/v1/auth/oidc/callback",
                BrowserSession.randomVerifier(), "")).statusCode()).isEqualTo(400);
    }

    private String verifiedAccount() {
        BrowserSession s = new BrowserSession(base());
        String email = "ama." + UUID.randomUUID() + "@example.com";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("firstName", "Ama");
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
        s.form("/signup/verify", Map.of("code", code,
                "_csrf", BrowserSession.csrfOf(s.get(base() + "/signup/verify").body())));
        return email;
    }

    private String authorizeUrl(String redirectUri, String verifier, String extra) {
        return base() + "/oauth2/authorize?response_type=code&client_id=" + BFF + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid profile email offline_access") + "&state=st-" + UUID.randomUUID()
                + "&nonce=n-1&code_challenge=" + BrowserSession.challenge(verifier) + "&code_challenge_method=S256"
                + extra;
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : base() + location;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
