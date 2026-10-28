package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP side of S262 / S263: the public visit counter and the staff attribution read, UTM tags on the subscribe
 * requests, the audited switch of a location, and the featured products an advertorial carries to the shop front.
 * Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class AttributionApiIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    record Res(int status, JsonNode body) {
    }

    private final RestClient http;
    private final JsonMapper json;
    private final MutableClock clock;
    private final LocationService locations;
    private final RunService runs;

    @Autowired
    AttributionApiIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, LocationService locations,
                     RunService runs) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.locations = locations;
        this.runs = runs;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    @BeforeEach
    void now() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @AfterEach
    void resetActor() {
        ItSupport.reset();
    }

    @Test
    void visitsAndSignUpsArriveOverHttpAndStaffReadThemPerSourceMediumAndCampaign() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        String visits = "/v1/public/locations/" + bastrop.slug() + "/visits";

        assertThat(call(HttpMethod.POST, visits, Map.of("runId", run.id().toString(), "utmSource", "WhatsApp",
                "utmMedium", "social", "utmCampaign", "delivery-run-bastrop-20261024")).status()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, visits, Map.of("utmSource", "whatsapp", "utmMedium", "social",
                "utmCampaign", "delivery-run-bastrop-20261024")).status()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, visits, Map.of()).status()).as("untagged: accepted, not counted")
                .isEqualTo(204);
        assertThat(call(HttpMethod.POST, "/v1/public/locations/nowhere/visits", Map.of("utmSource", "sms"))
                .status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, visits, Map.of("utmSource", "x".repeat(81))).status())
                .as("longer than the request allows").isEqualTo(400);

        Res sub = call(HttpMethod.POST, "/v1/public/subscriptions", Map.of("locationId", bastrop.id().toString(),
                "email", "ada@example.com", "source", "location-page", "utmSource", "whatsapp", "utmMedium", "social",
                "utmCampaign", "delivery-run-bastrop-20261024", "runId", run.id().toString()));
        assertThat(sub.status()).isEqualTo(202);

        Res report = call(HttpMethod.GET, "/v1/locations/" + bastrop.id() + "/attribution", null);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().path("visits").asLong()).isEqualTo(2);
        assertThat(report.body().path("signups").asLong()).as("the email is not confirmed yet").isZero();
        JsonNode row = report.body().path("rows").get(0);
        assertThat(row.path("utmSource").asString()).isEqualTo("whatsapp");
        assertThat(row.path("utmMedium").asString()).isEqualTo("social");
        assertThat(row.path("utmCampaign").asString()).isEqualTo("delivery-run-bastrop-20261024");
        assertThat(row.path("visits").asLong()).isEqualTo(2);

        Res forRun = call(HttpMethod.GET, "/v1/locations/" + bastrop.id() + "/attribution?runId=" + run.id()
                + "&days=7", null);
        assertThat(forRun.body().path("visits").asLong()).isEqualTo(1);
        assertThat(forRun.body().path("runId").asString()).isEqualTo(run.id().toString());
        assertThat(call(HttpMethod.GET, "/v1/locations/" + UUID.randomUUID() + "/attribution", null).status())
                .isEqualTo(404);
    }

    @Test
    void aLocationIsSwitchedOffWithAReasonAndBackOnOverHttp() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        String off = "/v1/locations/" + bastrop.id() + "/deactivate";

        Res blank = call(HttpMethod.POST, off, Map.of("reason", "  "));
        assertThat(blank.status()).as("the reason is required").isEqualTo(400);
        Res live = call(HttpMethod.POST, off, Map.of("reason", "Seasonal"));
        assertThat(live.status()).isEqualTo(409);
        assertThat(live.body().path("code").asString()).isEqualTo("delivery-runs.location-has-live-runs");

        runs.cancel(run.id(), "Storm warning");
        Res done = call(HttpMethod.POST, off, Map.of("reason", "Seasonal"));
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().path("active").asBoolean()).isFalse();
        Res back = call(HttpMethod.POST, "/v1/locations/" + bastrop.id() + "/activate", Map.of());
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.body().path("active").asBoolean()).isTrue();
        assertThat(call(HttpMethod.POST, "/v1/locations/" + bastrop.id() + "/activate", null).status())
                .as("the body is optional").isEqualTo(200);
    }

    @Test
    void featuredProductsTravelFromStaffToTheVisitorsCard() {
        Location bastrop = location();
        Run run = openRun(bastrop);
        Res created = call(HttpMethod.POST, "/v1/advertorials", Map.of("source", "RUN", "sourceId",
                run.id().toString(), "variant", "F", "headline", "Fresh from Lagos", "ctaLabel", "Order now",
                "featuredProducts", List.of(Map.of("atin", "ATSHEA0001", "title", "Raw shea butter", "imageUrl",
                        "https://img.test/shea.jpg"), Map.of("atin", "ATGARI0001", "title", "Garri, 2 kg"))));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("featuredProducts")).extracting(p -> p.path("atin").asString())
                .containsExactly("ATSHEA0001", "ATGARI0001");
        Res tooMany = call(HttpMethod.POST, "/v1/advertorials", Map.of("source", "RUN", "sourceId",
                run.id().toString(), "variant", "G", "headline", "Too many", "ctaLabel", "Order now",
                "featuredProducts", List.of(product(1), product(2), product(3), product(4), product(5))));
        assertThat(tooMany.status()).isEqualTo(400);
        Res badImage = call(HttpMethod.POST, "/v1/advertorials", Map.of("source", "RUN", "sourceId",
                run.id().toString(), "variant", "H", "headline", "Bad picture", "ctaLabel", "Order now",
                "featuredProducts", List.of(Map.of("atin", "ATSHEA0001", "title", "Shea", "imageUrl",
                        "http://img.test/shea.jpg"))));
        assertThat(badImage.status()).isEqualTo(422);

        String id = created.body().path("id").asString();
        assertThat(call(HttpMethod.POST, "/v1/advertorials/" + id + "/publish", null).status()).isEqualTo(200);
        Res served = call(HttpMethod.GET, "/v1/public/advertorials?zip=78602&visitor=v-featured&limit=10", null);
        JsonNode card = null;
        for (JsonNode item : served.body().path("items")) {
            if (item.path("id").asString().equals(id)) {
                card = item;
            }
        }
        // The generated A variant and the staff's F variant share visitors by weight: ask until F is the one served.
        for (int i = 0; card == null && i < 50; i++) {
            for (JsonNode item : call(HttpMethod.GET, "/v1/public/advertorials?zip=78602&limit=10&visitor=v" + i,
                    null).body().path("items")) {
                if (item.path("id").asString().equals(id)) {
                    card = item;
                }
            }
        }
        assertThat(card).as("some visitor gets the staff variant").isNotNull();
        assertThat(card.path("featuredProducts")).hasSize(2);
        assertThat(card.path("featuredProducts").get(0).path("title").asString()).isEqualTo("Raw shea butter");
        assertThat(card.path("featuredProducts").get(0).path("imageUrl").asString())
                .isEqualTo("https://img.test/shea.jpg");
        assertThat(card.path("ctaUrl").asString()).contains("src=page&cr=adv-" + id.replace("-", ""));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Map<String, String> product(int n) {
        return Map.of("atin", "ATPROD000" + n, "title", "Product " + n);
    }

    private Location location() {
        return locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
    }

    private Run openRun(Location location) {
        Run draft = runs.create(Fixtures.run(location.id(), clock.instant(), Duration.ofDays(3), 2));
        runs.announce(draft.id());
        return runs.open(draft.id());
    }

    private Res call(HttpMethod method, String uri, Object body) {
        RestClient.RequestBodySpec spec = http.method(method).uri(uri);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes()));
    }

    private Res read(int status, byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (RuntimeException e) {
            node = json.createObjectNode();
        }
        return new Res(status, node);
    }
}
