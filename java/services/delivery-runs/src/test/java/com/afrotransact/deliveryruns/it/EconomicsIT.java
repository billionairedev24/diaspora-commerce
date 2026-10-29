package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
import com.afrotransact.deliveryruns.application.RunMoneyService;
import com.afrotransact.deliveryruns.application.RunProgressService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.WindowCommand;
import com.afrotransact.deliveryruns.application.WindowView;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.port.MonthlyCount;
import com.afrotransact.deliveryruns.port.NetGrowth;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.deliveryruns.port.SubscriptionRepository;
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
    private final RunProgressService progress;
    private final RunMoneyService money;
    private final SubscriptionRepository subscriptions;

    @Autowired
    EconomicsIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
                PreorderWindowService windows, PreorderDecisionService decisions,
                PreorderProjectionService projection, PreorderSchedulerService scheduler,
                PreorderWindowRepository windowRepository, LocationService locations, RunService runs,
                RunProgressService progress, RunMoneyService money, SubscriptionRepository subscriptions) {
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
        this.progress = progress;
        this.money = money;
        this.subscriptions = subscriptions;
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

        // The window closes and hands its three orders to a run; then it is cancelled with the run.
        clock.set(f.closes.plusSeconds(60));
        assertThat(scheduler.process(f.window.id())).isPositive();
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

        // The location's history shows the run it was handed to as cancelled: orders refunded, no fees earned.
        JsonNode history = call(HttpMethod.GET, "/v1/locations/" + c.path("locationId").asString() + "/history", null,
                null).body();
        JsonNode cancelledRun = null;
        for (JsonNode run : history.path("runs")) {
            if (run.path("status").asString().equals("CANCELLED") && run.path("ordersRefunded").asInt() == 3) {
                cancelledRun = run;
            }
        }
        assertThat(cancelledRun).as("the cancelled run is in the location's history").isNotNull();
        assertThat(cancelledRun.path("feeRevenueMinor").asLong()).isZero();
        assertThat(cancelledRun.path("orders").asInt()).isEqualTo(3);

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

    // ---- S265 closing the audit gaps -----------------------------------------------------------------------------

    /** What a completed run's orders paid: [orderId, fee, total, kind]. */
    private static final String[][] MIX = {
        {"door-1", "799", "4799", "DOOR"}, {"door-2", "999", "3999", "DOOR"}, {"pickup", "0", "3000", "PICKUP"},
        {"carrier", "1299", "5299", "CARRIER"}, {"refunded", "799", "3799", "DOOR"}};

    @Test
    void aCompletedRunsPnlIsBuiltFromItsRealOrdersAndPlatformFeesAreTheirOwnLine() {
        assertThat(put(SETTINGS, withPlatformFee(200, "Estimate until payments reports"), null).status())
                .isEqualTo(200);
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Run run = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(3), 2)).id());
        String prefix = UUID.randomUUID().toString().substring(0, 8) + "-";
        for (String[] o : MIX) {
            assertThat(reserve(run.id(), prefix + o[0], o[1], o[2], o[3]).status()).isEqualTo(201);
        }
        assertThat(reserve(run.id(), prefix + "gave-back", "799", "2500", "DOOR").status()).isEqualTo(201);
        assertThat(call(HttpMethod.DELETE, "/v1/service/runs/" + run.id() + "/reservations/" + prefix + "gave-back",
                null, null).status()).as("the customer gave the slot back before the cutoff").isEqualTo(200);

        Res before = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pnl", null, null);
        assertThat(before.body().path("phase").asString()).isEqualTo("BEFORE");
        assertThat(before.body().path("actuals").path("ordersDelivered").asInt()).isEqualTo(5);
        assertThat(before.body().path("actuals").path("ordersCancelled").asInt()).isEqualTo(1);
        assertThat(before.body().path("actuals").path("deliveryFeesMinor").asLong()).as("799+999+0+1299+799")
                .isEqualTo(3896);

        // the run is confirmed at its cutoff, the driver collects, drives and delivers; payments reports as it goes
        clock.set(run.terms().cutoffAt().plusSeconds(60));
        runs.confirm(run.id(), "Go");
        Instant start = run.terms().window().start();
        clock.set(start);
        for (String[] o : MIX) {
            progress.onShipmentMoved(UUID.randomUUID(), prefix + o[0], "shp-" + o[0], "picked_up", start);
        }
        clock.set(start.plus(Duration.ofHours(3)));
        for (String[] o : MIX) {
            progress.onShipmentDelivered(UUID.randomUUID(), prefix + o[0], "shp-" + o[0], clock.instant());
        }
        assertThat(runs.get(run.id()).status()).isEqualTo(RunStatus.COMPLETED);
        money.onCaptured(UUID.randomUUID(), prefix + "door-1", 450, clock.instant());
        money.onCaptured(UUID.randomUUID(), prefix + "door-2", 380, clock.instant());
        UUID refund = UUID.randomUUID();
        money.onRefunded(refund, prefix + "refunded", 2299, 799, clock.instant());
        money.onRefunded(refund, prefix + "refunded", 2299, 799, clock.instant());

        Res after = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pnl", null, null);
        assertThat(after.status()).isEqualTo(200);
        JsonNode pnl = after.body();
        assertThat(pnl.path("phase").asString()).isEqualTo("AFTER");
        assertThat(pnl.path("status").asString()).isEqualTo("COMPLETED");
        JsonNode a = pnl.path("actuals");
        assertThat(a.path("ordersCarried").asInt()).isEqualTo(5);
        assertThat(a.path("ordersDelivered").asInt()).isEqualTo(5);
        assertThat(a.path("ordersFailed").asInt()).isZero();
        assertThat(a.path("ordersCancelled").asInt()).as("the slot given back, shown apart").isEqualTo(1);
        assertThat(a.path("ordersRefunded").asInt()).as("the refund is counted once, not twice").isEqualTo(1);
        assertThat(pnl.path("ordersRefunded").asInt()).isEqualTo(1);
        assertThat(a.path("feeMix")).extracting(m -> m.path("kind").asString(), m -> m.path("orders").asInt(),
                m -> m.path("feeMinor").asLong()).containsExactly(tuple("DOOR", 3, 2597L), tuple("PICKUP", 1, 0L),
                tuple("CARRIER", 1, 1299L));
        assertThat(a.path("deliveryFeesMinor").asLong()).isEqualTo(3896);
        assertThat(a.path("refundedFeesMinor").asLong()).as("from the refund payments issued").isEqualTo(799);
        assertThat(pnl.path("refundedFeesMinor").asLong()).isEqualTo(799);
        assertThat(a.path("netDeliveryFeesMinor").asLong()).isEqualTo(3097);
        assertThat(a.path("feesEstimated").asBoolean()).isFalse();
        assertThat(a.path("customersPaidMinor").asLong()).isEqualTo(4799 + 3999 + 3000 + 5299 + 3799);
        assertThat(a.path("totalsComplete").asBoolean()).isTrue();
        assertThat(a.path("refundsMinor").asLong()).isEqualTo(2299);

        // the driver was out 180 minutes: that, not the estimate, prices the time; miles stay estimated (64 x 70)
        assertThat(a.path("costBasis").asString()).isEqualTo("ACTUAL_TIME");
        assertThat(a.path("actualMinutes").asDouble()).isEqualTo(180.0);
        assertThat(a.path("estimatedCostMinor").asLong()).isEqualTo(180 * 2500 / 60 + 64 * 70);
        assertThat(a.path("contributionMarginMinor").asLong()).isEqualTo(3097 - (180 * 2500 / 60 + 64 * 70));
        assertThat(pnl.path("economics").path("atOrders").path("contributionMarginMinor").asLong())
                .isEqualTo(a.path("contributionMarginMinor").asLong());
        assertThat(pnl.path("economics").path("breakEvenOrders").asInt()).as("at the run's own fee").isEqualTo(18);
        assertThat(a.path("breakEvenOrders").asInt()).as("at the 619 an order actually paid").isGreaterThan(18);
        assertThat(a.path("breakEvenCovered").asBoolean()).isFalse();

        // platform fees on goods: its own line, from payments where reported, the estimate for the other three
        JsonNode fees = pnl.path("platformFees");
        assertThat(fees.path("source").asString()).isEqualTo("MIXED");
        assertThat(fees.path("commissionMinor").asLong()).isEqualTo(450 + 380 + 3 * 200);
        assertThat(fees.path("ordersReported").asInt()).isEqualTo(2);
        assertThat(fees.path("orders").asInt()).isEqualTo(5);
        assertThat(fees.path("perOrderEstimateMinor").asLong()).isEqualTo(200);
        assertThat(pnl.path("economics").path("atOrders").path("commissionShareMinor").isNull())
                .as("never mixed into the delivery P&L").isTrue();
        Res typed = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pnl?commissionShareMinor=999", null, null);
        assertThat(typed.body().path("platformFees").path("source").asString()).isEqualTo("MANUAL");
        assertThat(typed.body().path("platformFees").path("commissionMinor").asLong()).isEqualTo(999);
        assertThat(typed.body().path("actuals").path("contributionMarginMinor").asLong())
                .isEqualTo(a.path("contributionMarginMinor").asLong());

        // the location's history carries the same figures for the run, with whether the minimum covered break-even
        JsonNode summary = historyRun(location, run.id());
        assertThat(summary.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(summary.path("ordersDelivered").asInt()).isEqualTo(5);
        assertThat(summary.path("ordersCancelled").asInt()).isEqualTo(1);
        assertThat(summary.path("ordersRefunded").asInt()).isEqualTo(pnl.path("ordersRefunded").asInt());
        assertThat(summary.path("feeRevenueMinor").asLong()).isEqualTo(3097);
        assertThat(summary.path("refundedFeesMinor").asLong()).isEqualTo(799);
        assertThat(summary.path("costMinor").asLong()).isEqualTo(a.path("estimatedCostMinor").asLong());
        assertThat(summary.path("marginMinor").asLong()).isEqualTo(a.path("contributionMarginMinor").asLong());
        assertThat(summary.path("breakEvenOrders").asInt()).isEqualTo(18);
        assertThat(summary.path("minimumCoveredBreakEven").asBoolean()).as("a minimum of 2 is below 18").isFalse();
        assertThat(summary.path("phase").asString()).isEqualTo("AFTER");
    }

    @Test
    void aCancelledRunRefundsTheFeesItsOrdersWereChargedAndHistoryCountsThemTheSameWay() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Run run = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(3), 5)).id());
        String prefix = UUID.randomUUID().toString().substring(0, 8) + "-";
        reserve(run.id(), prefix + "a", "799", "3000", "DOOR");
        reserve(run.id(), prefix + "b", "1299", "5000", "CARRIER");
        reserve(run.id(), prefix + "c", "0", "2000", "PICKUP");
        runs.cancel(run.id(), "Storm warning");

        JsonNode pnl = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pnl", null, null).body();
        assertThat(pnl.path("phase").asString()).isEqualTo("CANCELLED");
        assertThat(pnl.path("ordersRefunded").asInt()).isEqualTo(3);
        assertThat(pnl.path("refundedFeesMinor").asLong()).as("799 + 1299 + 0 actually charged, not 3 x 799")
                .isEqualTo(2098);
        assertThat(pnl.path("refundedFeesEstimated").asBoolean()).isFalse();
        assertThat(pnl.path("actuals").isNull()).isTrue();
        assertThat(pnl.path("platformFees").isNull()).isTrue();

        JsonNode summary = historyRun(location, run.id());
        assertThat(summary.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(summary.path("phase").asString()).isEqualTo("CANCELLED");
        assertThat(summary.path("ordersRefunded").asInt()).as("the same orders-refunded as the P&L").isEqualTo(3);
        assertThat(summary.path("refundedFeesMinor").asLong()).isEqualTo(2098);
        assertThat(summary.path("feeRevenueMinor").asLong()).isZero();
        assertThat(summary.path("costMinor").asLong()).isZero();
        assertThat(summary.path("marginMinor").asLong()).isZero();
        assertThat(summary.path("ordersDelivered").asInt()).isZero();
        assertThat(summary.path("feesEstimated").asBoolean()).isFalse();

        // a run whose orders never reported what they paid says its refunded fees are an estimate
        Run quiet = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(5), 5)).id());
        assertThat(call(HttpMethod.POST, "/v1/service/runs/" + quiet.id() + "/reservations",
                Map.of("orderId", prefix + "q1", "customerId", "cust-q1"), null).status()).isEqualTo(201);
        assertThat(call(HttpMethod.POST, "/v1/service/runs/" + quiet.id() + "/reservations",
                Map.of("orderId", prefix + "q2", "customerId", "cust-q2"), null).status()).isEqualTo(201);
        runs.cancel(quiet.id(), "Driver unavailable");
        JsonNode estimated = call(HttpMethod.GET, "/v1/runs/" + quiet.id() + "/pnl", null, null).body();
        assertThat(estimated.path("ordersRefunded").asInt()).isEqualTo(2);
        assertThat(estimated.path("refundedFeesMinor").asLong()).isEqualTo(2 * 799);
        assertThat(estimated.path("refundedFeesEstimated").asBoolean()).as("the run's fee stands in").isTrue();
        assertThat(historyRun(location, quiet.id()).path("feesEstimated").asBoolean()).isTrue();
    }

    @Test
    void theLocationHistoryTrendsRunsAndShowsEachWindowWithItsOrdersFeesAndMinimum() {
        Fixture f = fixture("History #1", 2, 3);
        clock.set(f.closes.plusSeconds(60));
        assertThat(scheduler.process(f.window.id())).isPositive();
        PreorderWindow handed = windowRepository.findById(f.window.id()).orElseThrow();
        UUID location = runs.get(handed.runId()).locationId();

        JsonNode history = call(HttpMethod.GET, "/v1/locations/" + location + "/history", null, null).body();
        JsonNode window = null;
        for (JsonNode w : history.path("windows")) {
            if (w.path("id").asString().equals(f.window.id().toString())) {
                window = w;
            }
        }
        assertThat(window).as("the window is in the history of the location it was delivered by").isNotNull();
        assertThat(window.path("orders").asInt()).isEqualTo(3);
        assertThat(window.path("deliveryFeesMinor").asLong()).isEqualTo(3 * 799);
        assertThat(window.path("revenueMinor").asLong()).isEqualTo(3 * 2799);
        assertThat(window.path("minOrders").asInt()).isEqualTo(2);
        assertThat(window.path("minimumMet").asBoolean()).isTrue();
        assertThat(window.path("ordersRefunded").asInt()).isZero();

        // the run it became carries the window's real per-order fees, so its history row has a cost and a margin
        JsonNode run = historyRun(location, handed.runId());
        assertThat(run.path("ordersDelivered").asInt()).isEqualTo(3);
        assertThat(run.path("feeRevenueMinor").asLong()).isEqualTo(3 * 799);
        assertThat(run.path("costMinor").asLong()).isPositive();
        assertThat(run.path("marginMinor").asLong()).isEqualTo(3 * 799 - run.path("costMinor").asLong());
        assertThat(run.path("breakEvenOrders").asInt()).isEqualTo(18);

        // once the window is cancelled its row shows the orders it refunded and no revenue
        decisions.cancel(f.window.id(), "Seller stock fell through", null);
        JsonNode again = call(HttpMethod.GET, "/v1/locations/" + location + "/history", null, null).body();
        for (JsonNode w : again.path("windows")) {
            if (w.path("id").asString().equals(f.window.id().toString())) {
                assertThat(w.path("status").asString()).isEqualTo("CANCELLED");
                assertThat(w.path("orders").asInt()).isZero();
                assertThat(w.path("ordersRefunded").asInt()).isEqualTo(3);
                assertThat(w.path("refundedMinor").asLong()).isEqualTo(3 * 2799);
                assertThat(w.path("deliveryFeesMinor").asLong()).isZero();
            }
        }
    }

    @Test
    void subscriberGrowthIsNetOfThePeopleWhoLeft() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Instant jul = Instant.parse("2026-07-10T12:00:00Z");
        Instant aug = Instant.parse("2026-08-10T12:00:00Z");
        Instant sep = Instant.parse("2026-09-10T12:00:00Z");
        Instant oct = Instant.parse("2026-10-10T12:00:00Z");
        // July: two sign up. August: one signs up, one of July's leaves. September: nobody joins, two leave.
        // October: one joins; another of July's signs up again after leaving (still counts as a subscriber).
        Subscription a = signUp(location, "a", jul);
        Subscription b = signUp(location, "b", jul);
        Subscription c = signUp(location, "c", aug);
        Subscription d = signUp(location, "d", oct);
        subscriptions.update(a.withdraw(Set.of(), aug.plusSeconds(3600)));
        subscriptions.update(b.withdraw(Set.of(), sep));
        subscriptions.update(c.withdraw(Set.of(), sep.plusSeconds(60)));
        Subscription e = signUp(location, "e", jul);
        Subscription gone = e.withdraw(Set.of(), aug.plusSeconds(60));
        subscriptions.update(gone);
        subscriptions.update(subscriptions.findById(e.id()).orElseThrow().grant(Set.of(Channel.EMAIL), "returned",
                oct));
        assertThat(subscriptions.findById(d.id())).isPresent();
        Instant since = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(subscriptions.growth(location, since)).as("gross sign-ups by creation month").containsExactly(
                new MonthlyCount(YearMonth.of(2026, 7), 3), new MonthlyCount(YearMonth.of(2026, 8), 1),
                new MonthlyCount(YearMonth.of(2026, 10), 1));
        List<NetGrowth> net = subscriptions.netGrowth(location, since);
        assertThat(net).extracting(NetGrowth::month, NetGrowth::signUps, NetGrowth::unsubscribes, NetGrowth::net)
                .containsExactly(tuple(YearMonth.of(2026, 7), 3L, 0L, 3L), tuple(YearMonth.of(2026, 8), 1L, 1L, 0L),
                        tuple(YearMonth.of(2026, 9), 0L, 2L, -2L), tuple(YearMonth.of(2026, 10), 1L, 0L, 1L));
        assertThat(subscriptions.netGrowth(location, Instant.parse("2026-09-01T00:00:00Z"))).extracting(
                NetGrowth::month, NetGrowth::net).containsExactly(tuple(YearMonth.of(2026, 9), -2L),
                tuple(YearMonth.of(2026, 10), 1L));
    }

    @Test
    void theHistoryEndpointServesGrowthNetOfUnsubscribesForTheLastYear() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Instant now = clock.instant();
        signUp(location, "stays", now);
        Subscription leaves = signUp(location, "leaves", now);
        subscriptions.update(leaves.withdraw(Set.of(), now.plusSeconds(60)));

        JsonNode history = call(HttpMethod.GET, "/v1/locations/" + location + "/history", null, null).body();
        JsonNode month = history.path("subscriberNetGrowth").get(0);
        assertThat(month.path("month").asString()).isEqualTo(YearMonth.from(now.atZone(ZoneOffset.UTC)).toString());
        assertThat(month.path("signUps").asLong()).isEqualTo(2);
        assertThat(month.path("unsubscribes").asLong()).isEqualTo(1);
        assertThat(month.path("net").asLong()).isEqualTo(1);
        assertThat(history.path("subscriberGrowth").get(0).path("count").asLong()).as("gross is unchanged")
                .isEqualTo(2);
    }

    @Test
    void theWizardShowsTheMarginAtCapacityWhenTheCapacityIsKnown() {
        String id = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, 40,
                CutoffPolicy.CANCEL)).id().toString();

        Res known = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("locationId", id, "door", true,
                "minOrders", 5, "capacityOrders", 30), null);
        assertThat(known.status()).isEqualTo(200);
        JsonNode atCapacity = known.body().path("economics").path("atCapacity");
        assertThat(atCapacity.path("orders").asInt()).isEqualTo(30);
        assertThat(atCapacity.path("contributionMarginMinor").asLong()).isEqualTo(30 * 799
                - atCapacity.path("estimatedCostMinor").asLong());
        assertThat(known.body().path("capacityOrders").asInt()).isEqualTo(30);
        assertThat(known.body().path("economics").path("breakEvenOrders").asInt()).isEqualTo(18);
        assertThat(atCapacity.path("contributionMarginMinor").asLong()).isPositive();

        Res byDefault = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("locationId", id, "door", true,
                "minOrders", 5), null);
        assertThat(byDefault.body().path("capacityOrders").asInt()).as("the location's default capacity applies")
                .isEqualTo(40);
        assertThat(byDefault.body().path("economics").path("atCapacity").path("orders").asInt()).isEqualTo(40);

        String unlimited = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id().toString();
        Res none = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("locationId", unlimited, "door",
                true, "minOrders", 5), null);
        assertThat(none.body().path("economics").path("atCapacity").isNull()).as("not limited: no figure").isTrue();
        assertThat(none.body().path("capacityOrders").isNull()).isTrue();

        Res bad = call(HttpMethod.POST, "/v1/preorders/economics-preview", Map.of("locationId", id,
                "capacityOrders", 0), null);
        assertThat(bad.status()).isEqualTo(400);
    }

    @Test
    void thePlatformFeeEstimateIsStaffConfiguredWithAReasonAndAudited() {
        Res first = call(HttpMethod.GET, SETTINGS, null, null);
        assertThat(first.body().path("platformFeePerOrderMinor").asLong()).isZero();
        long version = first.body().path("version").asLong();

        Res saved = put(SETTINGS, withPlatformFee(250, "Commission averages $2.50 an order"), "\"" + version + "\"");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().path("platformFeePerOrderMinor").asLong()).isEqualTo(250);
        assertThat(put(SETTINGS, withPlatformFee(-1, "Typo"), null).status()).isIn(400, 422);
        assertThat(put(SETTINGS, withPlatformFee(100_001, "Typo"), null).status()).isIn(400, 422);

        JsonNode latest = call(HttpMethod.GET, SETTINGS + "/changes", null, null).body().path("items").get(0);
        assertThat(latest.path("reason").asString()).isEqualTo("Commission averages $2.50 an order");
        assertThat(latest.path("before").path("platformFeePerOrderMinor").asLong()).isZero();
        assertThat(latest.path("after").path("platformFeePerOrderMinor").asLong()).isEqualTo(250);
        assertThat(latest.path("actor").asString()).isEqualTo("local-dev");

        // a window with no payments data yet is estimated at the configured fee per order and labelled so
        Fixture f = fixture("Fees #1", 2, 3);
        JsonNode e = call(HttpMethod.GET, "/v1/preorders/" + f.window.id() + "/economics", null, null).body();
        assertThat(e.path("platformFees").path("source").asString()).isEqualTo("ESTIMATE");
        assertThat(e.path("platformFees").path("commissionMinor").asLong()).isEqualTo(3 * 250);
        assertThat(e.path("economics").path("atOrders").path("commissionShareMinor").isNull()).isTrue();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Map<String, Object> withPlatformFee(long platformFeeMinor, String reason) {
        Map<String, Object> b = settings(2500, 70, reason);
        b.put("platformFeePerOrderMinor", platformFeeMinor);
        return b;
    }

    /** Reserves a slot through the service API, telling delivery-runs what the order is charged. */
    private Res reserve(UUID runId, String orderId, String feeMinor, String totalMinor, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("customerId", "cust-" + orderId);
        body.put("allowNextRun", false);
        body.put("feeMinor", Long.parseLong(feeMinor));
        body.put("totalMinor", Long.parseLong(totalMinor));
        body.put("deliveryKind", kind);
        return call(HttpMethod.POST, "/v1/service/runs/" + runId + "/reservations", body, null);
    }

    private JsonNode historyRun(UUID location, UUID runId) {
        JsonNode history = call(HttpMethod.GET, "/v1/locations/" + location + "/history", null, null).body();
        for (JsonNode r : history.path("runs")) {
            if (r.path("id").asString().equals(runId.toString())) {
                return r;
            }
        }
        throw new AssertionError("run " + runId + " is not in the history of " + location);
    }

    private Subscription signUp(UUID location, String who, Instant at) {
        return subscriptions.insert(Subscription.forUser(UUID.randomUUID(), location, "user-" + who + "-"
                + UUID.randomUUID(), Set.of(Channel.EMAIL), "test", UUID.randomUUID().toString().replace("-", ""),
                at));
    }

    private record Fixture(PreorderWindow window, Instant closes, List<String> orderIds) {
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
        return new Fixture(windowRepository.findById(w.id()).orElseThrow(), now.plus(Duration.ofDays(3)), List.copyOf(ids));
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
