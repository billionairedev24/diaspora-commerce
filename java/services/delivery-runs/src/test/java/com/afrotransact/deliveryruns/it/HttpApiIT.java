package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP API end to end: staff, public, shopper and service endpoints, RFC 9457 problems, OpenAPI document. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class HttpApiIT {

    /** Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker. */
    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    /** Status and parsed body of a call. */
    record Res(int status, JsonNode body, String contentType) {
    }

    private final RestClient http;
    private final JsonMapper json;
    private final MutableClock clock;
    private final FakeGeo geo;

    @Autowired
    HttpApiIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeGeo geo) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.geo = geo;
    }

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @AfterEach
    void geoUp() {
        geo.down(false);
    }

    @Test
    void staffScheduleARunThatShoppersFindAndContentAdvertises() {
        String slug = Fixtures.slug("bastrop");
        Res location = post("/v1/locations", locationBody(slug));
        assertThat(location.status()).isEqualTo(201);
        String locationId = location.body().path("id").asString();
        assertThat(location.body().path("area").path("kind").asString()).isEqualTo("RADIUS");

        Instant cutoff = clock.instant().plus(Duration.ofDays(3));
        Instant start = cutoff.plus(Duration.ofDays(2));
        Map<String, Object> runBody = new LinkedHashMap<>();
        runBody.put("locationId", locationId);
        runBody.put("windowStart", start.toString());
        runBody.put("windowEnd", start.plus(Duration.ofHours(4)).toString());
        runBody.put("cutoffAt", cutoff.toString());
        Res run = post("/v1/runs", runBody);
        assertThat(run.status()).isEqualTo(201);
        String runId = run.body().path("id").asString();
        assertThat(run.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(run.body().path("feeMinor").asLong()).isEqualTo(799);
        assertThat(run.body().path("estimate").path("roadMiles").asDouble()).isEqualTo(32);
        assertThat(run.body().path("breakEvenOrders").asInt()).isPositive();

        Res preview = post("/v1/runs/economics-preview", Map.of("locationId", locationId, "expectedOrders", 25));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().path("breakEvenOrders").asInt()).isEqualTo(run.body().path("breakEvenOrders")
                .asInt());
        assertThat(preview.body().path("atOrders").path("feeRevenueMinor").asLong()).isEqualTo(25 * 799);

        assertThat(post("/v1/runs/" + runId + "/open", Map.of()).body().path("status").asString()).isEqualTo("OPEN");
        Res invalid = post("/v1/runs/" + runId + "/announce", Map.of());
        assertThat(invalid.status()).isEqualTo(409);
        assertThat(invalid.contentType()).startsWith("application/problem+json");
        assertThat(invalid.body().path("code").asString()).isEqualTo("delivery-runs.invalid-transition");
        assertThat(invalid.body().path("type").asString()).endsWith("/problems/delivery-runs.invalid-transition");

        Res available = get("/v1/public/runs/available?zip=78602");
        assertThat(available.status()).isEqualTo(200);
        assertThat(available.body().path("runs").valueStream().map(r -> r.path("runId").asString()))
                .contains(runId);
        // S261: checkout shows the run's day, minimum progress and the rule behind its fee.
        JsonNode offered = available.body().path("runs").valueStream()
                .filter(r -> runId.equals(r.path("runId").asString())).findFirst().orElseThrow();
        assertThat(offered.path("deliversOn").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(offered.path("timeZone").asString()).isEqualTo("America/Chicago");
        assertThat(offered.path("minOrders").asInt()).isPositive();
        assertThat(offered.path("ordersSoFar").asInt()).isZero();
        assertThat(offered.path("minimumMet").asBoolean()).isFalse();
        assertThat(offered.path("feeRule").path("method").asString()).isEqualTo("AFROTRANSACT_DELIVERY");
        assertThat(offered.path("feeRule").path("amountMinor").asLong()).isEqualTo(799);
        assertThat(offered.path("feeRule").path("source").asString()).isEqualTo("RULE");
        Res byPoint = get("/v1/public/runs/available?lat=30.11&lng=-97.31");
        assertThat(byPoint.body().path("runs").valueStream().map(r -> r.path("runId").asString())).contains(runId);

        Res ad = get("/v1/public/runs/" + runId + "/advertorial");
        assertThat(ad.status()).isEqualTo(200);
        assertThat(ad.body().path("town").asString()).isEqualTo("Bastrop");
        assertThat(ad.body().path("feeDisplay").asString()).isEqualTo("$7.99");
        assertThat(ad.body().path("acceptingOrders").asBoolean()).isTrue();
        assertThat(Instant.parse(ad.body().path("countdownTarget").asString())).isEqualTo(cutoff);
        assertThat(Instant.parse(ad.body().path("expiresAt").asString())).isEqualTo(cutoff);
        assertThat(ad.body().path("ctaUrl").asString())
                .startsWith("http://storefront.test/delivery-runs/" + slug + "?run=" + runId)
                .contains("utm_medium=advertorial");

        Res page = get("/v1/public/locations/" + slug);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("location").path("whatsappInviteUrl").asString())
                .isEqualTo("https://chat.whatsapp.com/BastropRuns");
        assertThat(page.body().path("shareLinks").path("facebook").asString())
                .startsWith("https://www.facebook.com/sharer/sharer.php?u=");
        assertThat(page.body().path("runs").get(0).path("runId").asString()).isEqualTo(runId);

        Res reserved = post("/v1/service/runs/" + runId + "/reservations",
                Map.of("orderId", "ord-http-1", "customerId", "cust-1", "packages", 2));
        assertThat(reserved.status()).isEqualTo(201);
        assertThat(reserved.body().path("slot").path("status").asString()).isEqualTo("RESERVED");
        assertThat(post("/v1/service/runs/" + runId + "/reservations",
                Map.of("orderId", "ord-http-1", "customerId", "cust-1", "packages", 2)).status()).isEqualTo(200);
        assertThat(get("/v1/runs/" + runId + "/orders").body().size()).isEqualTo(1);

        Res draftAd = get("/v1/public/runs/" + post("/v1/runs", runBody).body().path("id").asString()
                + "/advertorial");
        assertThat(draftAd.status()).as("draft runs are not public").isEqualTo(404);

        assertThat(post("/v1/public/subscriptions", Map.of("locationId", locationId, "email", "ada@example.com"))
                .status()).isEqualTo(202);
        assertThat(get("/v1/locations/" + locationId + "/subscribers/counts").body().path("pending").asLong())
                .isEqualTo(1);
        assertThat(get("/v1/locations/" + locationId + "/history").body().path("runs").size()).isEqualTo(2);
    }

    @Test
    void problemsForRulesValidationAndGeoOutages() {
        Res bad = post("/v1/locations", Map.of("slug", "Not A Slug", "name", "X", "market", "US"));
        assertThat(bad.status()).isEqualTo(400);

        String locationId = post("/v1/locations", locationBody(Fixtures.slug("bastrop"))).body().path("id")
                .asString();
        Instant cutoff = clock.instant().plus(Duration.ofDays(3));
        Res rule = post("/v1/runs", Map.of("locationId", locationId, "windowStart", cutoff.minusSeconds(3600).toString(),
                "windowEnd", cutoff.plusSeconds(3600).toString(), "cutoffAt", cutoff.toString()));
        assertThat(rule.status()).isEqualTo(422);
        assertThat(rule.body().path("code").asString()).isEqualTo("delivery-runs.invalid-cutoff");

        assertThat(get("/v1/public/runs/available").body().path("code").asString())
                .isEqualTo("delivery-runs.address-required");
        assertThat(get("/v1/public/runs/available?zip=12").status()).isEqualTo(422);

        geo.down(true);
        Res down = get("/v1/public/runs/available?zip=78602");
        assertThat(down.status()).isEqualTo(502);
        assertThat(down.body().path("code").asString()).isEqualTo("delivery-runs.geo-unavailable");

        assertThat(get("/v1/runs/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
        assertThat(get("/v3/api-docs").body().path("paths").has("/v1/public/runs/{id}/advertorial")).isTrue();
    }

    private static Map<String, Object> locationBody(String slug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", slug);
        body.put("name", "Bastrop");
        body.put("market", "US");
        body.put("state", "TX");
        body.put("areaKind", "RADIUS");
        body.put("centerLat", 30.1105);
        body.put("centerLng", -97.3153);
        body.put("radiusMiles", 12);
        body.put("defaultMinOrders", 2);
        body.put("whatsappInviteUrl", "https://chat.whatsapp.com/BastropRuns");
        return body;
    }

    private Res get(String uri) {
        return http.get().uri(uri).exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes(),
                res.getHeaders().getContentType()));
    }

    private Res post(String uri, Object body) {
        return http.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body))
                .exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes(),
                        res.getHeaders().getContentType()));
    }

    private Res read(int status, byte[] bytes, MediaType type) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonNode node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        return new Res(status, node, type == null ? "" : type.toString());
    }
}
