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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
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
 * S265 against real PostgreSQL: the driver cost model is staff-editable with a reason and an audit trail and the
 * economics follow it at once; a run's P&amp;L before it, after it, and when it was cancelled (orders refunded, nothing
 * earned); a window's economics at its actual orders and when cancelled; and the break-even shown beside a window's
 * minimum while it is being created. Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class EconomicsIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final String SETTINGS = "/v1/economics-settings";

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
    private final PreorderWindowRepository windowRepository;
    private final LocationService locations;
    private final RunService runs;

    @Autowired
    EconomicsIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
                PreorderWindowService windows, PreorderDecisionService decisions,
                PreorderProjectionService projection, PreorderSchedulerService scheduler,
                PreorderWindowRepository windowRepository, LocationService locations, RunService runs) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.upstreams = upstreams;
        this.windows = windows;
        this.decisions = decisions;
        this.projection = projection;
        this.scheduler = scheduler;
        this.windowRepository = windowRepository;
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
    void restoreTheDefaults() {
        ItSupport.reset();
        put(SETTINGS, settings(2500, 70, "Test clean-up"), null);
    }

    private static Map<String, Object> settings(long perHour, long perMile, String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("driverCostPerHourMinor", perHour);
        b.put("driverCostPerMileMinor", perMile);
        b.put("collectionMinutes", 45);
        b.put("perStopMinutes", 6);
        b.put("pickupPointStopMinutes", 30);
        b.put("handoverMinutesPerOrder", 1);
        b.put("roundTrip", true);
        if (reason != null) {
            b.put("reason", reason);
        }
        return b;
    }

    @Test
    void theCostModelIsEditableWithAReasonAuditedAndEconomicsFollowAtOnce() {
        Res first = call(HttpMethod.GET, SETTINGS, null, null);
        assertThat(first.status()).isEqualTo(200);
        long version = first.body().path("version").asLong();
        assertThat(first.etag()).isEqualTo("\"" + version + "\"");
        assertThat(first.body().path("driverCostPerHourMinor").asLong()).isEqualTo(2500);
        assertThat(first.body().path("source").asString()).isIn("CONFIG", "STAFF");

        String location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id().toString();
        Map<String, Object> ask = Map.of("locationId", location, "expectedOrders", 20);
        int before = call(HttpMethod.POST, "/v1/runs/economics-preview", ask, null).body().path("breakEvenOrders")
                .asInt();
        assertThat(before).isEqualTo(18);

        assertThat(put(SETTINGS, settings(3000, 80, null), null).status()).isIn(400, 422);
        assertThat(put(SETTINGS, settings(3000, 80, "   "), null).status()).isIn(400, 422);
        assertThat(put(SETTINGS, settings(-1, 80, "Typo"), null).status()).isIn(400, 422);
        assertThat(put(SETTINGS, settings(100_001, 80, "Typo"), null).status()).isIn(400, 422);

        Res saved = put(SETTINGS, settings(4000, 90, "Austin drivers moved to overtime"), "\"" + version + "\"");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.etag()).isEqualTo("\"" + (version + 1) + "\"");
        assertThat(saved.body().path("source").asString()).isEqualTo("STAFF");
        assertThat(saved.body().path("updatedBy").asString()).isEqualTo("local-dev");
        assertThat(saved.body().path("reason").asString()).isEqualTo("Austin drivers moved to overtime");

        Res stale = put(SETTINGS, settings(4100, 90, "Stale"), "\"" + version + "\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("code").asString()).isEqualTo("delivery-runs.version-mismatch");

        int after = call(HttpMethod.POST, "/v1/runs/economics-preview", ask, null).body().path("breakEvenOrders")
                .asInt();
        assertThat(after).as("a dearer driver needs more orders to break even").isGreaterThan(before);

        Res same = put(SETTINGS, settings(4000, 90, "Looked again"), null);
        assertThat(same.body().path("version").asLong()).as("no change, no new version").isEqualTo(version + 1);

        Res changes = call(HttpMethod.GET, SETTINGS + "/changes", null, null);
        JsonNode latest = changes.body().path("items").get(0);
        assertThat(latest.path("version").asLong()).isEqualTo(version + 1);
        assertThat(latest.path("reason").asString()).isEqualTo("Austin drivers moved to overtime");
        assertThat(latest.path("actor").asString()).isEqualTo("local-dev");
        assertThat(latest.path("before").path("driverCostPerHourMinor").asLong()).isEqualTo(2500);
        assertThat(latest.path("after").path("driverCostPerHourMinor").asLong()).isEqualTo(4000);
    }

    @Test
    void aRunsPnlBeforeAfterAndWhenCancelled() {
        String slug = Fixtures.slug("bastrop");
        UUID location = locations.create(Fixtures.bastrop(slug, DropMode.DOORSTEP, null, CutoffPolicy.CANCEL)).id();
        Instant now = clock.instant();
        Run run = runs.create(Fixtures.run(location, now, Duration.ofDays(3), 2));

        Res before = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pnl", null, null);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().path("phase").asString()).isEqualTo("BEFORE");
        assertThat(before.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(before.body().path("economics").path("breakEvenOrders").asInt()).isEqualTo(18);
        assertThat(before.body().path("economics").path("atOrders").path("orders").asInt()).isZero();
        assertThat(before.body().path("ordersRefunded").asInt()).isZero();

        runs.cancel(run.id(), "Storm warning");
        Res cancelled = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pnl", null, null);
        assertThat(cancelled.body().path("phase").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().path("cancelReason").asString()).isEqualTo("Storm warning");
        assertThat(cancelled.body().path("economics").path("atOrders").path("revenueMinor").asLong()).isZero();
        assertThat(cancelled.body().path("economics").path("atOrders").path("estimatedCostMinor").asLong()).isZero();

        assertThat(call(HttpMethod.GET, "/v1/runs/" + UUID.randomUUID() + "/pnl", null, null).status())
                .isEqualTo(404);
    }

    @Test
    void aWindowReportsRevenueFeesCostAndBreakEvenAndWhenCancelledTheOrdersRefunded() {
        Fixture f = fixture("Economics #1", 2, 3);
        String id = f.window.id().toString();

        Res open = call(HttpMethod.GET, "/v1/preorders/" + id + "/economics", null, null);
        assertThat(open.status()).isEqualTo(200);
        JsonNode e = open.body();
        assertThat(e.path("cancelled").asBoolean()).isFalse();
        assertThat(e.path("orders").asInt()).isEqualTo(3);
        assertThat(e.path("deliveryFeesMinor").asLong()).isEqualTo(3 * 799);
        assertThat(e.path("revenueMinor").asLong()).isEqualTo(3 * 2799);
        assertThat(e.path("locationName").asString()).isEqualTo("Bastrop");
        long cost = e.path("economics").path("atOrders").path("estimatedCostMinor").asLong();
        assertThat(cost).isPositive();
        assertThat(e.path("economics").path("atOrders").path("contributionMarginMinor").asLong())
                .isEqualTo(3 * 799 - cost);
        assertThat(e.path("economics").path("breakEvenOrders").asInt()).isEqualTo(18);
        assertThat(e.path("minimumCoversBreakEven").asBoolean()).as("a minimum of 2 is below break-even").isFalse();
        assertThat(e.path("ordersRefunded").asInt()).isZero();

        decisions.cancel(f.window.id(), "Seller stock fell through", null);
        Res cancelled = call(HttpMethod.GET, "/v1/preorders/" + id + "/economics", null, null);
        JsonNode c = cancelled.body();
        assertThat(c.path("cancelled").asBoolean()).isTrue();
        assertThat(c.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(c.path("ordersRefunded").asInt()).isEqualTo(3);
        assertThat(c.path("refundedMinor").asLong()).isEqualTo(3 * 2799);
        assertThat(c.path("revenueMinor").asLong()).as("refunded, not revenue").isZero();
        assertThat(c.path("orders").asInt()).isZero();
        assertThat(c.path("economics").isNull()).isTrue();
        assertThat(c.path("refundsSettled").asInt()).isZero();

        projection.onRefund(UUID.randomUUID(), f.orderIds.get(0), RefundStatus.REFUNDED, 2799L, null, clock.instant());
        assertThat(call(HttpMethod.GET, "/v1/preorders/" + id + "/economics", null, null).body()
                .path("refundsSettled").asInt()).isEqualTo(1);

        assertThat(call(HttpMethod.GET, "/v1/preorders/" + UUID.randomUUID() + "/economics", null, null).status())
                .isEqualTo(404);
    }

    @Test
    void theWizardSeesBreakEvenBesideTheMinimumBeforeTheWindowExists() {
        locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null, CutoffPolicy.CANCEL));

        Res low = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("targets", List.of("US/TX/Bastrop"),
                "door", true, "minOrders", 5), null);
        assertThat(low.status()).isEqualTo(200);
        assertThat(low.body().path("locationName").asString()).isEqualTo("Bastrop");
        assertThat(low.body().path("feeMinor").asLong()).isEqualTo(799);
        assertThat(low.body().path("economics").path("breakEvenOrders").asInt()).isEqualTo(18);
        assertThat(low.body().path("minimumCoversBreakEven").asBoolean()).isFalse();
        assertThat(low.body().path("economics").path("atMinimum").path("orders").asInt()).isEqualTo(5);
        assertThat(low.body().path("economics").path("atMinimum").path("contributionMarginMinor").asLong())
                .isNegative();

        Res high = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("targets", List.of("US/TX/Bastrop"),
                "door", true, "minOrders", 25), null);
        assertThat(high.body().path("minimumCoversBreakEven").asBoolean()).isTrue();
        assertThat(high.body().path("economics").path("atMinimum").path("contributionMarginMinor").asLong())
                .isPositive();

        Res dearFee = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("targets", List.of(
                "US/TX/Bastrop"), "door", true, "doorFeeMinor", 1500, "minOrders", 5), null);
        assertThat(dearFee.body().path("feeMinor").asLong()).isEqualTo(1500);
        assertThat(dearFee.body().path("economics").path("breakEvenOrders").asInt()).isLessThan(18);

        Res nowhere = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("targets", List.of(
                "US/TX/Nowhereville"), "minOrders", 5), null);
        assertThat(nowhere.status()).isEqualTo(200);
        assertThat(nowhere.body().path("economics").isNull()).isTrue();
        assertThat(nowhere.body().path("locationId").isNull()).isTrue();

        assertThat(call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("targets", List.of("US/TX/Bastrop"),
                "minOrders", -2), null).status()).isEqualTo(400);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private record Fixture(PreorderWindow window, List<String> orderIds) {
    }

    /** An open Bastrop window with {@code orders} projected orders and a location. */
    private Fixture fixture(String name, int minimum, int orders) {
        locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null, CutoffPolicy.CANCEL));
        Instant now = clock.instant();
        PreorderWindow w = publish(name, "US/TX/Bastrop", now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)),
                minimum);
        scheduler.process(w.id());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String order = UUID.randomUUID().toString();
            ids.add(order);
            projection.onOrderPlaced(UUID.randomUUID(), placed(order, w.id(), now));
        }
        return new Fixture(windowRepository.findById(w.id()).orElseThrow(), List.copyOf(ids));
    }

    private PreorderWindow publish(String name, String target, Instant opens, Instant closes, int minOrders) {
        LocalDate delivers = closes.plus(Duration.ofDays(2)).atZone(CHICAGO).toLocalDate();
        WindowView v = windows.create(new WindowCommand(name, List.of(target), opens, closes, delivers,
                "America/Chicago", name + " is open", "Fresh egusi delivered", "AfroTransact: pre-order open",
                Set.of("Pantry", "Spices"), true, true, null, null, true, 20_000L, Set.of(CommKind.ANNOUNCE), null,
                null, null, minOrders));
        WindowView withHero = windows.setCreative(v.window().id(), CreativeSlot.HERO, upstreams.image(1920, 640),
                v.window().version());
        return windows.publish(v.window().id(), withHero.window().version()).window();
    }

    private static OrderPlaced placed(String orderId, UUID windowId, Instant at) {
        return new OrderPlaced(orderId, "AT-" + orderId.substring(0, 6), "cust-" + orderId.substring(0, 4), "USD", at,
                "Shopper", "+1 806 555 0100", "shopper@example.test", "1 Main St", "Bastrop", "TX", "78602", null,
                "sms", null, null, List.of(new OrderPlaced.Package("shp-" + orderId, "seller-1", windowId,
                PreorderFulfilment.DOOR, 2799, 799, List.of(new PreorderLine("EGU", "Egusi", "seller-1", 2)))));
    }

    private Res put(String uri, Object body, String ifMatch) {
        return call(HttpMethod.PUT, uri, body, ifMatch);
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
