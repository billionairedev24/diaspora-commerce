package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.ExtendCommand;
import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.OrderPlaced;
import com.afrotransact.deliveryruns.application.PreorderDecisionService;
import com.afrotransact.deliveryruns.application.PreorderProjectionService;
import com.afrotransact.deliveryruns.application.PreorderSchedulerService;
import com.afrotransact.deliveryruns.application.PreorderWindowService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.WindowCommand;
import com.afrotransact.deliveryruns.application.WindowView;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
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
 * S263 against real PostgreSQL: a published window or an announced run drafts its advertorial from the events this
 * service publishes; visitors in the area see it with the closing countdown (and only while ordering is open); staff
 * add A/B variants that split visitors by weight; it ends when the window closes or is cancelled; views, clicks and
 * attributed orders are counted. Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class AdvertorialIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    record Res(int status, JsonNode body, String etag) {
    }

    private final RestClient http;
    private final JsonMapper json;
    private final MutableClock clock;
    private final FakeUpstreams upstreams;
    private final PreorderWindowService windows;
    private final PreorderDecisionService decisions;
    private final PreorderProjectionService projection;
    private final PreorderSchedulerService scheduler;
    private final LocationService locations;
    private final RunService runs;

    @Autowired
    AdvertorialIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
                  PreorderWindowService windows, PreorderDecisionService decisions,
                  PreorderProjectionService projection, PreorderSchedulerService scheduler,
                  LocationService locations, RunService runs) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.upstreams = upstreams;
        this.windows = windows;
        this.decisions = decisions;
        this.projection = projection;
        this.scheduler = scheduler;
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
    void aPublishedWindowDraftsItsAdvertorialAndVisitorsInItsAreaSeeItWithTheCountdown() {
        Fixture f = fixture("Adv Window #1", "Plainview", 2);
        String id = f.window.id().toString();

        Res list = call(HttpMethod.GET, "/v1/advertorials?source=WINDOW&sourceId=" + id, null, null);
        assertThat(list.body().path("items")).hasSize(1);
        JsonNode a = list.body().path("items").get(0);
        assertThat(a.path("variant").asString()).isEqualTo("A");
        assertThat(a.path("generated").asBoolean()).isTrue();
        assertThat(a.path("status").asString()).isEqualTo("LIVE");
        assertThat(a.path("createdBy").asString()).isEqualTo("system");
        assertThat(a.path("headline").asString()).isNotBlank();
        assertThat(a.path("sourceCode").asString()).isEqualTo(f.window.code());
        assertThat(a.path("serving").asBoolean()).as("the window is open").isTrue();

        Res served = call(HttpMethod.GET, "/v1/public/advertorials?country=US&region=TX&city=" + f.city() + "&visitor=v-1",
                null, null);
        assertThat(served.status()).isEqualTo(200);
        assertThat(served.body().path("items")).hasSize(1);
        JsonNode s = served.body().path("items").get(0);
        assertThat(s.path("id").asString()).isEqualTo(a.path("id").asString());
        assertThat(s.path("source").asString()).isEqualTo("WINDOW");
        assertThat(Instant.parse(s.path("countdownTarget").asString())).isEqualTo(f.closes);
        assertThat(Instant.parse(s.path("expiresAt").asString())).isEqualTo(f.closes);
        String creative = "adv-" + a.path("id").asString().replace("-", "");
        String url = s.path("ctaUrl").asString();
        assertThat(url).startsWith("http://storefront.test/pre-orders?src=page&cr=" + creative + "&po=" + f.window.code())
                .contains("utm_medium=advertorial").contains("utm_campaign=preorder-" + f.window.code())
                .contains("utm_content=" + creative);
        assertThat(s.path("deliveryDisplay").asString()).isNotBlank();

        assertThat(call(HttpMethod.GET, "/v1/public/advertorials?country=US&region=TX&city=Lubbock", null, null)
                .body().path("items")).as("another town").isEmpty();
        assertThat(call(HttpMethod.GET, "/v1/public/advertorials", null, null).body().path("items")).isEmpty();
    }

    @Test
    void variantsSplitVisitorsByWeightAndAVisitorKeepsTheirs() {
        Fixture f = fixture("Adv Variants #2", "Plainview", 2);
        String id = f.window.id().toString();
        Res draft = call(HttpMethod.POST, "/v1/advertorials", Map.of("source", "WINDOW", "sourceId", id,
                "weight", 1, "priority", 5, "headline", "Plainview, your order closes soon", "body", "Egusi, garri",
                "ctaLabel", "Order now"), null);
        assertThat(draft.status()).isEqualTo(201);
        assertThat(draft.body().path("variant").asString()).as("the next free label").isEqualTo("B");
        assertThat(draft.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.body().path("generated").asBoolean()).isFalse();
        String b = draft.body().path("id").asString();

        assertThat(servedVariants(f, 20)).as("a draft is not served").containsExactly("A");
        Res live = call(HttpMethod.POST, "/v1/advertorials/" + b + "/publish", null, null);
        assertThat(live.body().path("status").asString()).isEqualTo("LIVE");
        Set<String> seen = servedVariants(f, 60);
        assertThat(seen).as("equal weights reach both variants").containsExactlyInAnyOrder("A", "B");

        String one = variantFor(f, "visitor-77");
        for (int i = 0; i < 5; i++) {
            assertThat(variantFor(f, "visitor-77")).as("a visitor keeps the variant they were given").isEqualTo(one);
        }

        Res paused = call(HttpMethod.POST, "/v1/advertorials/" + b + "/pause", null, null);
        assertThat(paused.body().path("status").asString()).isEqualTo("PAUSED");
        assertThat(servedVariants(f, 20)).containsExactly("A");
        assertThat(call(HttpMethod.POST, "/v1/advertorials/" + b + "/pause", null, null).status()).isEqualTo(409);

        Res taken = call(HttpMethod.POST, "/v1/advertorials", Map.of("source", "WINDOW", "sourceId", id, "variant",
                "B", "headline", "Again", "ctaLabel", "Order"), null);
        assertThat(taken.status()).isEqualTo(422);
        assertThat(taken.body().path("code").asString()).isEqualTo("delivery-runs.advertorial-variant-taken");
    }

    @Test
    void staffEditsWithAVersionAndGenerateIsIdempotent() {
        Fixture f = fixture("Adv Edit #3", "Plainview", 2);
        String id = f.window.id().toString();
        Res again = call(HttpMethod.POST, "/v1/advertorials/generate", Map.of("source", "WINDOW", "sourceId", id),
                null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/v1/advertorials?source=WINDOW&sourceId=" + id, null, null).body()
                .path("items")).as("still one generated advertorial").hasSize(1);
        String a = again.body().path("id").asString();
        long version = again.body().path("version").asLong();
        assertThat(again.etag()).isEqualTo("\"" + version + "\"");

        Map<String, Object> words = Map.of("headline", "Plainview, egusi is back", "body", "Order before Friday",
                "ctaLabel", "Pre-order now", "priority", 9);
        Res edited = call(HttpMethod.PUT, "/v1/advertorials/" + a, words, "\"" + version + "\"");
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().path("headline").asString()).isEqualTo("Plainview, egusi is back");
        assertThat(edited.body().path("generated").asBoolean()).as("staff took the words over").isFalse();
        assertThat(edited.body().path("priority").asInt()).isEqualTo(9);

        Res stale = call(HttpMethod.PUT, "/v1/advertorials/" + a, words, "\"" + version + "\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("code").asString()).isEqualTo("delivery-runs.version-mismatch");
        assertThat(call(HttpMethod.PUT, "/v1/advertorials/" + a, Map.of("headline", " ", "ctaLabel", "x"), null)
                .status()).isIn(400, 422);

        // The window's edit re-drafts only advertorials that still follow it.
        windows.update(f.window.id(), command("Adv Edit #3 renamed", "US/TX/" + f.city(), f.opens, f.closes,
                "Plainview renamed headline"), windows.get(f.window.id()).window().version());
        Res kept = call(HttpMethod.GET, "/v1/advertorials/" + a, null, null);
        assertThat(kept.body().path("headline").asString()).isEqualTo("Plainview, egusi is back");

        assertThat(call(HttpMethod.GET, "/v1/advertorials/" + UUID.randomUUID(), null, null).status())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/v1/advertorials/generate", Map.of("source", "WINDOW", "sourceId",
                UUID.randomUUID().toString()), null).status()).isEqualTo(404);
    }

    @Test
    void viewsClicksAndOrdersAreCounted() {
        Fixture f = fixture("Adv Results #4", "Plainview", 2);
        String a = call(HttpMethod.GET, "/v1/advertorials?source=WINDOW&sourceId=" + f.window.id(), null, null)
                .body().path("items").get(0).path("id").asString();
        for (int i = 0; i < 4; i++) {
            assertThat(call(HttpMethod.POST, "/v1/public/advertorials/" + a + "/events", Map.of("type", "VIEW"), null)
                    .status()).isEqualTo(204);
        }
        call(HttpMethod.POST, "/v1/public/advertorials/" + a + "/events", Map.of("type", "CLICK"), null);
        assertThat(call(HttpMethod.POST, "/v1/public/advertorials/" + UUID.randomUUID() + "/events",
                Map.of("type", "VIEW"), null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/v1/public/advertorials/" + a + "/events", Map.of("type", "LIKE"), null)
                .status()).isEqualTo(400);

        // Two orders came through the advertorial link (src=page&cr=adv-…), one by another route.
        String creative = "adv-" + a.replace("-", "");
        projection.onOrderPlaced(UUID.randomUUID(), placed(UUID.randomUUID().toString(), f.window.id(), clock
                .instant(), "page", creative, f.window.code()));
        projection.onOrderPlaced(UUID.randomUUID(), placed(UUID.randomUUID().toString(), f.window.id(), clock
                .instant(), "page", creative, f.window.code()));
        projection.onOrderPlaced(UUID.randomUUID(), placed(UUID.randomUUID().toString(), f.window.id(), clock
                .instant(), "ig", "story", f.window.code()));

        JsonNode r = call(HttpMethod.GET, "/v1/advertorials/" + a, null, null).body().path("results");
        assertThat(r.path("views").asLong()).isEqualTo(4);
        assertThat(r.path("clicks").asLong()).isEqualTo(1);
        assertThat(r.path("ctrPct").asDouble()).isEqualTo(25.0);
        assertThat(r.path("orders").asInt()).isEqualTo(2);
    }

    @Test
    void itEndsWhenTheWindowClosesOrIsCancelled() {
        Fixture closing = fixture("Adv Close #5", "Plainview", 1);
        clock.set(closing.closes.plusSeconds(60));
        assertThat(scheduler.process(closing.window.id())).isPositive();
        JsonNode closed = call(HttpMethod.GET, "/v1/advertorials?source=WINDOW&sourceId=" + closing.window.id(), null,
                null).body().path("items").get(0);
        assertThat(closed.path("status").asString()).isEqualTo("ENDED");
        assertThat(closed.path("ended").asString()).isEqualTo("SOURCE_CLOSED");
        assertThat(call(HttpMethod.GET, "/v1/public/advertorials?country=US&region=TX&city=" + closing.city(), null, null)
                .body().path("items")).extracting(i -> i.path("sourceId").asString())
                .doesNotContain(closing.window.id().toString());
        assertThat(call(HttpMethod.PUT, "/v1/advertorials/" + closed.path("id").asString(), Map.of("headline", "x",
                "ctaLabel", "y"), null).status()).as("an ended advertorial cannot be edited").isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/v1/advertorials/generate", Map.of("source", "WINDOW", "sourceId",
                closing.window.id().toString()), null).status()).as("nor generated again").isEqualTo(409);

        clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        Fixture cancelled = fixture("Adv Cancel #6", "Plainview", 3);
        assertThat(servedIds(cancelled)).contains(cancelled.window.id().toString());
        decisions.cancel(cancelled.window.id(), "Seller stock fell through", null);
        JsonNode ad = call(HttpMethod.GET, "/v1/advertorials?source=WINDOW&sourceId=" + cancelled.window.id(), null,
                null).body().path("items").get(0);
        assertThat(ad.path("status").asString()).isEqualTo("ENDED");
        assertThat(ad.path("ended").asString()).isEqualTo("CANCELLED");
        assertThat(servedIds(cancelled)).doesNotContain(cancelled.window.id().toString());
    }

    @Test
    void anAnnouncedRunDraftsItsAdvertorialAndOnlyItsCoverageSeesItWhileItTakesOrders() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Instant now = clock.instant();
        Run run = runs.create(Fixtures.run(location, now, Duration.ofDays(3), 2));
        assertThat(call(HttpMethod.GET, "/v1/advertorials?source=RUN&sourceId=" + run.id(), null, null).body()
                .path("items")).as("a draft run has none").isEmpty();

        runs.announce(run.id());
        runs.open(run.id());
        JsonNode a = call(HttpMethod.GET, "/v1/advertorials?source=RUN&sourceId=" + run.id(), null, null).body()
                .path("items").get(0);
        assertThat(a.path("generated").asBoolean()).isTrue();
        assertThat(a.path("headline").asString()).startsWith("AfroTransact delivery run to Bastrop");
        assertThat(a.path("results").path("orders").isNull()).as("run orders are not attributed").isTrue();

        Res inside = call(HttpMethod.GET, "/v1/public/advertorials?zip=78602&visitor=v1&limit=10", null, null);
        JsonNode s = null;
        for (JsonNode item : inside.body().path("items")) {
            if (item.path("sourceId").asString().equals(run.id().toString())) {
                s = item;
            }
        }
        assertThat(s).as("the run's advertorial is served in its area").isNotNull();
        assertThat(s.path("source").asString()).isEqualTo("RUN");
        assertThat(s.path("place").asString()).isEqualTo("Bastrop");
        assertThat(Instant.parse(s.path("countdownTarget").asString())).isEqualTo(run.terms().cutoffAt());
        assertThat(s.path("ctaUrl").asString()).contains("/delivery-runs/").contains("utm_medium=advertorial")
                .contains("utm_content=adv-");
        assertThat(call(HttpMethod.GET, "/v1/public/advertorials?zip=78701&limit=10", null, null).body().path("items"))
                .as("Austin is outside the run's area").extracting(i -> i.path("sourceId").asString())
                .doesNotContain(run.id().toString());

        runs.extend(run.id(), new ExtendCommand(run.terms().cutoffAt().plus(Duration.ofDays(1)), null, null, null,
                "More time"));
        JsonNode after = call(HttpMethod.GET, "/v1/advertorials?source=RUN&sourceId=" + run.id(), null, null).body()
                .path("items").get(0);
        assertThat(after.path("body").asString()).contains("Order by");

        runs.cancel(run.id(), "Storm warning");
        JsonNode ended = call(HttpMethod.GET, "/v1/advertorials?source=RUN&sourceId=" + run.id(), null, null).body()
                .path("items").get(0);
        assertThat(ended.path("status").asString()).isEqualTo("ENDED");
        assertThat(ended.path("ended").asString()).isEqualTo("CANCELLED");
        assertThat(call(HttpMethod.GET, "/v1/public/advertorials?zip=78602&limit=10", null, null).body().path("items"))
                .extracting(i -> i.path("sourceId").asString()).doesNotContain(run.id().toString());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private record Fixture(PreorderWindow window, Instant opens, Instant closes, String city) {
    }

    private Set<String> servedVariants(Fixture f, int visitors) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < visitors; i++) {
            Res r = call(HttpMethod.GET, "/v1/public/advertorials?country=US&region=TX&city=" + f.city() + "&visitor=visitor-"
                    + i, null, null);
            for (JsonNode item : r.body().path("items")) {
                if (item.path("sourceId").asString().equals(f.window.id().toString())) {
                    out.add(item.path("variant").asString());
                }
            }
        }
        return out;
    }

    private String variantFor(Fixture f, String visitor) {
        Res r = call(HttpMethod.GET, "/v1/public/advertorials?country=US&region=TX&city=" + f.city() + "&visitor="
                + visitor, null, null);
        return r.body().path("items").get(0).path("variant").asString();
    }

    private List<String> servedIds(Fixture f) {
        List<String> out = new ArrayList<>();
        call(HttpMethod.GET, "/v1/public/advertorials?country=US&region=TX&city=" + f.city() + "&limit=10", null, null)
                .body().path("items").forEach(i -> out.add(i.path("sourceId").asString()));
        return out;
    }

    /** An open Plainview window (publishing it drafts its advertorial) with {@code orders} projected orders. */
    private Fixture fixture(String name, String prefix, int days) {
        String city = prefix + UUID.randomUUID().toString().replaceAll("[^a-z]", "").substring(0, 6);
        Instant now = clock.instant();
        Instant closes = now.plus(Duration.ofDays(days));
        Instant opens = now.minus(Duration.ofDays(1));
        PreorderWindow w = publish(name, "US/TX/" + city, opens, closes);
        scheduler.process(w.id());
        return new Fixture(w, opens, closes, city);
    }

    private static WindowCommand command(String name, String target, Instant opens, Instant closes, String headline) {
        LocalDate delivers = closes.plus(Duration.ofDays(2)).atZone(CHICAGO).toLocalDate();
        return new WindowCommand(name, List.of(target), opens, closes, delivers, "America/Chicago",
                name + " is open", headline, "AfroTransact: pre-order open", Set.of("Pantry", "Spices"), true, true,
                null, null, true, 20_000L, Set.of(CommKind.ANNOUNCE), null, null, null, 0);
    }

    private PreorderWindow publish(String name, String target, Instant opens, Instant closes) {
        WindowView v = windows.create(command(name, target, opens, closes, "Fresh egusi delivered"));
        WindowView withHero = windows.setCreative(v.window().id(), CreativeSlot.HERO, upstreams.image(1920, 640),
                v.window().version());
        return windows.publish(v.window().id(), withHero.window().version()).window();
    }

    private static OrderPlaced placed(String orderId, UUID windowId, Instant at, String src, String creative,
                                      String code) {
        return new OrderPlaced(orderId, "AT-" + orderId.substring(0, 6), "cust-" + orderId.substring(0, 4), "USD", at,
                "Shopper", "+1 806 555 0100", "shopper@example.test", "1 Main St", "Plainview", "TX", "79072", null,
                src, creative, code, List.of(new OrderPlaced.Package("shp-" + orderId, "seller-1", windowId,
                PreorderFulfilment.DOOR, 2799, 799, List.of(new PreorderLine("EGU", "Egusi", "seller-1", 2)))));
    }

    private Res call(HttpMethod method, String uri, Object body, String ifMatch) {
        RestClient.RequestBodySpec spec = http.method(method).uri(uri);
        if (ifMatch != null) {
            spec = spec.header("If-Match", ifMatch);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes(),
                res.getHeaders().getETag()));
    }

    private Res read(int status, byte[] bytes, String etag) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (RuntimeException e) {
            node = json.createObjectNode();
        }
        return new Res(status, node, etag);
    }
}
