package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * S322 on identity's side: a referral code from a friend's link is prefilled on the hosted sign-up form and travels
 * with the sign-up (form or API) in {@code identity.user.registered}, together with keyed hashes of the sign-up device
 * and network for the referral program's abuse checks. Runs on {@code AT_IT_POSTGRES_URL} when set (no Docker needed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.identity.verification.sender=outbox"})
@ActiveProfiles("local")
class SignUpReferralIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final String BFF = "gateway-bff";
    private static final String CALLBACK = "http://localhost:3000/api/bff/v1/auth/oidc/callback";
    private static final String PASSWORD = "Plantain-Jollof-2026";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        if (EXTERNAL_DB == null) {
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

    @LocalServerPort
    private int port;

    private final JdbcClient jdbc;

    @Autowired
    SignUpReferralIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private String authorizeUrl(String extra) {
        return base() + "/oauth2/authorize?response_type=code&client_id=" + BFF + "&redirect_uri=" + enc(CALLBACK)
                + "&scope=" + enc("openid profile email offline_access") + "&state=st-" + UUID.randomUUID()
                + "&nonce=n-1&code_challenge=" + BrowserSession.challenge(BrowserSession.randomVerifier())
                + "&code_challenge_method=S256&prompt=create" + extra;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static Map<String, String> form(String csrf, String email, String referral) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("firstName", "Ama");
        form.put("lastName", "Mensah");
        form.put("email", email);
        form.put("phoneRegion", "US");
        form.put("phone", "");
        form.put("password", PASSWORD);
        form.put("confirmPassword", PASSWORD);
        form.put("termsAccepted", "true");
        form.put("referralCode", referral);
        form.put("_csrf", csrf);
        return form;
    }

    private JsonNode registered(String email) {
        String payload = jdbc.sql("select payload::text from platform_outbox where type = 'identity.user.registered' "
                + "and payload->>'email' = :e").param("e", email).query(String.class).single();
        return JSON.readTree(payload);
    }

    /** Opens the sign-up page of an authorization request (optionally with a referral parameter) and submits it. */
    private JsonNode signUp(BrowserSession browser, String authorizeExtra, String typedReferral, String email) {
        browser.get(authorizeUrl(authorizeExtra));
        HttpResponse<String> page = browser.get(base() + "/signup");
        assertThat(page.statusCode()).isEqualTo(200);
        HttpResponse<String> created = browser.form("/signup", form(BrowserSession.csrfOf(page.body()), email,
                typedReferral));
        assertThat(BrowserSession.location(created)).endsWith("/signup/verify");
        return registered(email);
    }

    @Test
    void theLinkPrefillsTheFormAndTheCodeTravelsWithTheRegistration() {
        BrowserSession browser = new BrowserSession(base());
        browser.get(authorizeUrl("&referral=KDM7-XR3Q"));
        HttpResponse<String> page = browser.get(base() + "/signup");
        assertThat(page.body()).contains("name=\"referralCode\"", "value=\"KDM7-XR3Q\"", "Referral code");
        assertThat(browser.cookies().getCookieStore().getCookies()).extracting(c -> c.getName()).contains("at_device");

        String email = "ama." + UUID.randomUUID() + "@example.com";
        HttpResponse<String> created = browser.form("/signup", form(BrowserSession.csrfOf(page.body()), email,
                "KDM7-XR3Q"));
        assertThat(BrowserSession.location(created)).endsWith("/signup/verify");
        JsonNode event = registered(email);
        assertThat(event.path("referralCode").asString()).isEqualTo("KDM7-XR3Q");
        assertThat(event.path("deviceHash").asString()).matches("[0-9a-f]{64}");
        assertThat(event.path("networkHash").asString()).matches("[0-9a-f]{64}");
        assertThat(event.toString()).as("the raw device id is never published").doesNotContain("at_device");
    }

    @Test
    void aCodeTypedByHandIsUsedAndNoCodeIsNull() {
        JsonNode typed = signUp(new BrowserSession(base()), "", "bcdf2345", "typed." + UUID.randomUUID() + "@example.com");
        assertThat(typed.path("referralCode").asString()).isEqualTo("bcdf2345");
        JsonNode none = signUp(new BrowserSession(base()), "", "", "none." + UUID.randomUUID() + "@example.com");
        assertThat(none.path("referralCode").isNull() || none.path("referralCode").isMissingNode()).isTrue();
    }

    @Test
    void sameBrowserSameDeviceHashDifferentBrowserDifferentHash() {
        BrowserSession one = new BrowserSession(base());
        JsonNode first = signUp(one, "", "", "d1." + UUID.randomUUID() + "@example.com");
        JsonNode second = signUp(one, "", "", "d2." + UUID.randomUUID() + "@example.com");
        JsonNode other = signUp(new BrowserSession(base()), "", "", "d3." + UUID.randomUUID() + "@example.com");
        assertThat(second.path("deviceHash").asString()).isEqualTo(first.path("deviceHash").asString());
        assertThat(other.path("deviceHash").asString()).isNotEqualTo(first.path("deviceHash").asString());
        // the same machine and network: the network part matches across browsers
        assertThat(other.path("networkHash").asString()).isEqualTo(first.path("networkHash").asString());
    }

    @Test
    void theAccountApiAcceptsTheCodeToo() throws Exception {
        String email = "api." + UUID.randomUUID() + "@example.com";
        Map<String, Object> body = Map.of("firstName", "Kofi", "lastName", "Boateng", "email", email,
                "phone", "+17132490142", "password", PASSWORD, "confirmPassword", PASSWORD, "termsAccepted", true,
                "referralCode", "BCDF-2345");
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(base() + "/v1/accounts")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode event = registered(email);
        assertThat(event.path("referralCode").asString()).isEqualTo("BCDF-2345");
        assertThat(event.path("networkHash").asString()).matches("[0-9a-f]{64}");
        assertThat(event.path("deviceHash").isNull() || event.path("deviceHash").isMissingNode()).isTrue();
    }
}
