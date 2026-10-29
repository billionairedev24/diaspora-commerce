package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.OrderPlaced;
import com.afrotransact.deliveryruns.application.PreorderProjectionService;
import com.afrotransact.deliveryruns.application.PreorderSchedulerService;
import com.afrotransact.deliveryruns.application.PreorderWindowService;
import com.afrotransact.deliveryruns.application.RunProgressService;
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
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStage;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S264 against real PostgreSQL: a closed pre-order window becomes a run whose sellers get the pickup time and a pick
 * list of their own lines; the orders' shipment events (collected, on the road, delivered, failed, returned,
 * cancelled) move the run through collecting and out for delivery to completed, record each delivery's time, and are
 * safe to repeat, reorder and replay. Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.preorders.default-min-orders=1", "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class RunDeliveryIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Actor MAMA = new Actor("sm-mama", PrincipalType.SELLER_MEMBER, Optional.of("org-mama"),
            Set.of("seller.owner"), Set.of("orders:read"));
    private static final Actor NO_ORDERS = new Actor("sm-x", PrincipalType.SELLER_MEMBER, Optional.of("org-mama"),
            Set.of("seller.viewer"), Set.of("catalog:read"));

    record Res(int status, JsonNode body) {
    }

    private final RestClient http;
    private final JsonMapper json;
    private final MutableClock clock;
    private final FakeUpstreams upstreams;
    private final PreorderWindowService windows;
    private final PreorderProjectionService projection;
    private final PreorderSchedulerService scheduler;
    private final PreorderWindowRepository windowRepository;
    private final RunOrderRepository runOrders;
    private final LocationService locations;
    private final RunService runs;
    private final RunProgressService progress;
    private final JdbcClient jdbc;

    @Autowired
    RunDeliveryIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
                  PreorderWindowService windows, PreorderProjectionService projection,
                  PreorderSchedulerService scheduler, PreorderWindowRepository windowRepository,
                  RunOrderRepository runOrders, LocationService locations, RunService runs,
                  RunProgressService progress, JdbcClient jdbc) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.upstreams = upstreams;
        this.windows = windows;
        this.projection = projection;
        this.scheduler = scheduler;
        this.windowRepository = windowRepository;
        this.runOrders = runOrders;
        this.locations = locations;
        this.runs = runs;
        this.progress = progress;
        this.jdbc = jdbc;
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

    // ---- 1. a window closes into a run: pickup time and pick lists ------------------------------------------------

    @Test
    void aClosedWindowGivesSellersThePickupTimeAndAPickListOfTheirOwnLines() {
        Fixture f = closedWindow("Picks #1", 3);
        PreorderWindow w = windowRepository.findById(f.window().id()).orElseThrow();
        Run run = runs.get(w.runId());
        assertThat(run.status()).isEqualTo(RunStatus.CONFIRMED);
        Instant pickupAt = run.terms().sellerPickupAt();
        assertThat(pickupAt).as("the run schedules the sweep").isNotNull()
                .isAfterOrEqualTo(run.terms().cutoffAt()).isBefore(run.terms().window().start());

        // the confirmed event tells notifications whom to tell, with the pickup time
        JsonNode event = json.readTree(outboxPayloads("delivery-runs.run.confirmed", run.id().toString()).getFirst());
        assertThat(Instant.parse(event.path("sellerPickupAt").asString())).isEqualTo(pickupAt);
        assertThat(event.path("sellers")).extracting(s -> s.path("sellerOrgId").asString(),
                        s -> s.path("orders").asInt(), s -> s.path("units").asInt())
                .containsExactly(tuple("org-mama", 3, 7), tuple("org-lagos", 2, 3));

        // staff see every seller's list of the run
        Res all = call(HttpMethod.GET, "/v1/runs/" + run.id() + "/pick-lists");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().path("items")).extracting(i -> i.path("sellerOrgId").asString())
                .containsExactly("org-mama", "org-lagos");

        // a seller sees the pickup time and its own lines only, per order
        ItSupport.as(MAMA);
        Res mine = call(HttpMethod.GET, "/v1/seller/run-pickups");
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.body().path("items")).as("earliest pickup first; other tests' runs share this database")
                .extracting(i -> i.path("runId").asString()).contains(run.id().toString());
        JsonNode list = listOf(mine, run.id());
        assertThat(Instant.parse(list.path("sellerPickupAt").asString())).isEqualTo(pickupAt);
        assertThat(list.path("town").asString()).isEqualTo("Bastrop");
        assertThat(list.path("dropMode").asString()).isEqualTo("DOORSTEP");
        assertThat(list.path("orders").asInt()).isEqualTo(3);
        assertThat(list.path("units").asInt()).isEqualTo(7);
        assertThat(list.path("collectedOrders").asInt()).isZero();
        assertThat(list.path("items")).extracting(i -> i.path("atin").asString(), i -> i.path("units").asInt())
                .containsExactly(tuple("EGU", 6), tuple("YAM", 1));
        assertThat(list.path("bags")).hasSize(3);
        assertThat(list.path("bags")).extracting(b -> b.path("stage").asString()).containsOnly("PENDING");
        assertThat(list.toString()).as("a seller never sees the other seller's lines or customer details")
                .doesNotContain("PAL", "Palm oil", "Shopper", "shopper@example.test", "+1 806", "1 Main St");

        Res one = call(HttpMethod.GET, "/v1/seller/run-pickups/" + run.id());
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.body().path("sellerOrgId").asString()).isEqualTo("org-mama");

        // another organisation, or a member without orders access, is refused; so is a run with nothing to collect
        assertThat(call(HttpMethod.GET, "/v1/seller/run-pickups?orgId=org-lagos").status()).isEqualTo(403);
        ItSupport.as(NO_ORDERS);
        assertThat(call(HttpMethod.GET, "/v1/seller/run-pickups").status()).isEqualTo(403);
        ItSupport.reset();
        assertThat(call(HttpMethod.GET, "/v1/seller/run-pickups").status()).as("staff must name the organisation")
                .isEqualTo(422);
        Res lagos = call(HttpMethod.GET, "/v1/seller/run-pickups?orgId=org-lagos");
        assertThat(listOf(lagos, run.id()).path("units").asInt()).isEqualTo(3);
        assertThat(call(HttpMethod.GET, "/v1/seller/run-pickups/" + run.id() + "?orgId=org-nobody").status())
                .isEqualTo(404);
    }

    // ---- 2. real events move the run ------------------------------------------------------------------------------

    @Test
    void shipmentEventsMoveTheRunThroughCollectingOutForDeliveryAndCompleted() {
        Fixture f = closedWindow("Moves #1", 3);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        String id = runId.toString();
        Instant t = clock.instant();

        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "picked_up", t);
        assertThat(runs.get(runId).status()).as("the first collection starts the sweep").isEqualTo(RunStatus.COLLECTING);
        assertThat(runs.get(runId).milestones().collectingAt()).isNotNull();

        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "picked_up", t.plusSeconds(60));
        assertThat(runs.get(runId).status()).as("one order is still at its seller").isEqualTo(RunStatus.COLLECTING);
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "out_for_delivery", t.plusSeconds(61));
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(2), "shp-2", "picked_up", t.plusSeconds(120));
        assertThat(runs.get(runId).status()).as("the sweep is done").isEqualTo(RunStatus.OUT_FOR_DELIVERY);

        Instant first = t.plusSeconds(3600);
        UUID deliveredEvent = UUID.randomUUID();
        progress.onShipmentDelivered(deliveredEvent, f.orderIds().get(0), "shp-0", first);
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(1), "shp-1", first.plusSeconds(600));
        assertThat(runs.get(runId).status()).as("one order still to deliver").isEqualTo(RunStatus.OUT_FOR_DELIVERY);
        RunOrder one = runOrders.findActiveByOrderId(f.orderIds().get(0)).orElseThrow();
        assertThat(one.stage()).isEqualTo(RunOrderStage.DELIVERED);
        assertThat(one.deliveredAt()).as("the delivery time is the event's, not the time it was received")
                .isEqualTo(first);

        Instant last = first.plusSeconds(1800);
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(2), "shp-2", last);
        Run done = runs.get(runId);
        assertThat(done.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.milestones().completedAt()).isNotNull();
        assertThat(outboxTypes(id)).filteredOn(ty -> ty.matches("delivery-runs\\.run\\.(collecting|out_for_delivery|completed)"))
                .containsExactlyInAnyOrder("delivery-runs.run.collecting", "delivery-runs.run.out_for_delivery",
                        "delivery-runs.run.completed");
        JsonNode completed = json.readTree(outboxPayloads("delivery-runs.run.completed", id).getFirst());
        assertThat(completed.path("orders")).hasSize(3);

        Res p = call(HttpMethod.GET, "/v1/runs/" + id + "/progress");
        assertThat(p.body().path("orders").asInt()).isEqualTo(3);
        assertThat(p.body().path("delivered").asInt()).isEqualTo(3);
        assertThat(p.body().path("pending").asInt()).isZero();
        assertThat(Instant.parse(p.body().path("lastDeliveredAt").asString())).isEqualTo(last);
        Res slots = call(HttpMethod.GET, "/v1/runs/" + id + "/orders");
        assertThat(slots.body()).extracting(s -> s.path("stage").asString()).containsOnly("DELIVERED");
        assertThat(slots.body().get(0).path("deliveredAt").asString()).isNotBlank();
    }

    @Test
    void repeatedReplayedAndOutOfOrderEventsChangeNothing() {
        Fixture f = closedWindow("Idem #1", 2);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        String id = runId.toString();
        Instant t = clock.instant();

        UUID pickedUp = UUID.randomUUID();
        progress.onShipmentMoved(pickedUp, f.orderIds().get(0), "shp-0", "picked_up", t);
        progress.onShipmentMoved(pickedUp, f.orderIds().get(0), "shp-0", "picked_up", t);
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "picked_up", t.plusSeconds(5));
        assertThat(outboxTypes(id)).filteredOn("delivery-runs.run.collecting"::equals).hasSize(1);

        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "picked_up", t);
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(0), "shp-0", t.plusSeconds(100));
        // a late "out for delivery" and a late "picked up" for a delivered order do not move it back
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "out_for_delivery", t.plusSeconds(1));
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "picked_up", t.plusSeconds(1));
        assertThat(runOrders.findActiveByOrderId(f.orderIds().get(0)).orElseThrow().stage())
                .isEqualTo(RunOrderStage.DELIVERED);

        UUID lastDelivery = UUID.randomUUID();
        progress.onShipmentDelivered(lastDelivery, f.orderIds().get(1), "shp-1", t.plusSeconds(200));
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.COMPLETED);
        int events = outboxTypes(id).size();
        Instant deliveredAt = runOrders.findActiveByOrderId(f.orderIds().get(1)).orElseThrow().deliveredAt();

        // the same delivery again, a different event for the same delivery, and a delivered event much later
        progress.onShipmentDelivered(lastDelivery, f.orderIds().get(1), "shp-1", t.plusSeconds(200));
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(1), "shp-1", t.plusSeconds(999));
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "delivery_failed", t.plusSeconds(1000));
        assertThat(outboxTypes(id)).as("no second completed event").hasSize(events);
        assertThat(runOrders.findActiveByOrderId(f.orderIds().get(1)).orElseThrow().deliveredAt())
                .isEqualTo(deliveredAt);
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.COMPLETED);

        // other statuses and orders on no run are ignored
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "confirmed", t);
        progress.onShipmentMoved(UUID.randomUUID(), "order-on-no-run", "shp-x", "picked_up", t);
        progress.onShipmentDelivered(UUID.randomUUID(), "order-on-no-run", "shp-x", t);
    }

    @Test
    void aFailedAttemptKeepsTheRunOpenUntilItIsDeliveredOrBack() {
        Fixture f = closedWindow("Failed #1", 2);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        Instant t = clock.instant();
        for (int i = 0; i < 2; i++) {
            progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(i), "shp-" + i, "picked_up", t);
            progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(i), "shp-" + i, "out_for_delivery", t);
        }
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(0), "shp-0", t.plusSeconds(60));
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "delivery_failed", t.plusSeconds(90));
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.OUT_FOR_DELIVERY);
        assertThat(call(HttpMethod.GET, "/v1/runs/" + runId + "/progress").body().path("failed").asInt()).isEqualTo(1);

        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "out_for_delivery", t.plusSeconds(120));
        assertThat(runOrders.findActiveByOrderId(f.orderIds().get(1)).orElseThrow().stage())
                .isEqualTo(RunOrderStage.OUT_FOR_DELIVERY);
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "delivery_failed", t.plusSeconds(150));
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(1), "shp-1", "returned", t.plusSeconds(200));
        assertThat(runs.get(runId).status()).as("delivered and returned close the run").isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aCancelledShipmentLeavesTheRunAndTheLastOneWaitedForCompletesIt() {
        Fixture f = closedWindow("Cancel #1", 3);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        Instant t = clock.instant();
        for (int i = 0; i < 2; i++) {
            progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(i), "shp-" + i, "picked_up", t);
            progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(i), "shp-" + i, t.plusSeconds(30));
        }
        assertThat(runs.get(runId).status()).as("the third order has not been collected").isEqualTo(RunStatus.COLLECTING);

        UUID cancel = UUID.randomUUID();
        progress.onShipmentCancelled(cancel, f.orderIds().get(2), "shp-2", t.plusSeconds(60));
        progress.onShipmentCancelled(cancel, f.orderIds().get(2), "shp-2", t.plusSeconds(60));
        assertThat(runOrders.findByRun(runId, RunOrderStatus.CANCELLED)).hasSize(1);
        Run run = runs.get(runId);
        assertThat(run.reservedOrders()).isEqualTo(2);
        assertThat(run.status()).as("nothing left to wait for").isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aRunCancelledAsAWholeIsNotMovedByLateShipmentEvents() {
        Fixture f = closedWindow("Whole #1", 2);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        runs.cancel(runId, "Storm");
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "picked_up", clock.instant());
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(0), "shp-0", clock.instant());
        progress.onShipmentCancelled(UUID.randomUUID(), f.orderIds().get(1), "shp-1", clock.instant());
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(runOrders.findByRun(runId, null)).extracting(RunOrder::status)
                .containsOnly(RunOrderStatus.CANCELLED);
    }

    @Test
    void staffCanStillMoveARunByHandAndTheMoveIsAnnounced() {
        Fixture f = closedWindow("Manual #1", 1);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        assertThat(call(HttpMethod.POST, "/v1/runs/" + runId + "/start-collecting").status()).isEqualTo(200);
        assertThat(outboxTypes(runId.toString())).contains("delivery-runs.run.collecting");
        // events that arrive afterwards continue from there
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "picked_up", clock.instant());
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.OUT_FOR_DELIVERY);
        progress.onShipmentDelivered(UUID.randomUUID(), f.orderIds().get(0), "shp-0", clock.instant());
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void theDispatchListCarriesTheHubTheSlotsProgressAndThePickupTime() {
        Fixture f = closedWindow("Dispatch #1", 2);
        UUID runId = windowRepository.findById(f.window().id()).orElseThrow().runId();
        progress.onShipmentMoved(UUID.randomUUID(), f.orderIds().get(0), "shp-0", "picked_up", clock.instant());
        Res dispatch = call(HttpMethod.GET, "/v1/service/runs/" + runId + "/dispatch");
        assertThat(dispatch.status()).isEqualTo(200);
        assertThat(dispatch.body().path("location").path("originLat").asDouble()).isNotZero();
        assertThat(dispatch.body().path("location").path("originLng").asDouble()).isNotZero();
        assertThat(dispatch.body().path("sellerPickupAt").asString()).isNotBlank();
        assertThat(dispatch.body().path("orders")).extracting(o -> o.path("orderId").asString(),
                        o -> o.path("stage").asString())
                .containsExactlyInAnyOrder(tuple(f.orderIds().get(0), "COLLECTED"),
                        tuple(f.orderIds().get(1), "PENDING"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private record Fixture(PreorderWindow window, List<String> orderIds) {
    }

    /**
     * A Bastrop window with {@code orders} paid orders whose packages mix two sellers (org-mama: egusi, yam;
     * org-lagos: palm oil on the first two), closed and handed to a CONFIRMED run.
     */
    private Fixture closedWindow(String name, int orders) {
        locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null, CutoffPolicy.CANCEL));
        Instant now = clock.instant();
        Instant opens = now.minus(Duration.ofDays(2));
        Instant closes = now.plus(Duration.ofDays(3));
        PreorderWindow w = publish(name, "US/TX/Bastrop", opens, closes);
        assertThat(scheduler.process(w.id())).as("opened and announced").isEqualTo(2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String order = UUID.randomUUID().toString();
            ids.add(order);
            projection.onOrderPlaced(UUID.randomUUID(), placed(order, w.id(), now, i, "shp-" + i));
        }
        clock.set(closes.plusSeconds(60));
        assertThat(scheduler.process(w.id())).isPositive();
        assertThat(windowRepository.findById(w.id()).orElseThrow().runId()).isNotNull();
        return new Fixture(w, List.copyOf(ids));
    }

    private PreorderWindow publish(String name, String target, Instant opens, Instant closes) {
        LocalDate delivers = closes.plus(Duration.ofDays(2)).atZone(CHICAGO).toLocalDate();
        WindowView v = windows.create(new WindowCommand(name, List.of(target), opens, closes, delivers,
                "America/Chicago", name + " is open", "Fresh egusi delivered", "AfroTransact: pre-order open",
                Set.of("Pantry", "Spices"), true, true, null, null, true, 20_000L, Set.of(CommKind.ANNOUNCE), null,
                null, null, 1));
        WindowView withHero = windows.setCreative(v.window().id(), CreativeSlot.HERO, upstreams.image(1920, 640),
                v.window().version());
        return windows.publish(v.window().id(), withHero.window().version()).window();
    }

    /** Package {@code i}: 2 egusi for org-mama, plus 1 palm oil from org-lagos for the first two orders, and yams. */
    private static OrderPlaced placed(String orderId, UUID windowId, Instant at, int i, String shipment) {
        List<PreorderLine> lines = new ArrayList<>();
        lines.add(new PreorderLine("EGU", "Egusi", "org-mama", "Mama Tee's Pantry", 2));
        if (i < 2) {
            lines.add(new PreorderLine("PAL", "Palm oil", "org-lagos", "Lagos Oils", 1 + (i == 0 ? 1 : 0)));
        }
        if (i == 0) {
            lines.add(new PreorderLine("YAM", "Yam", "org-mama", "Mama Tee's Pantry", 1));
        }
        return new OrderPlaced(orderId, "AT-" + orderId.substring(0, 6), "cust-" + orderId.substring(0, 4), "USD", at,
                "Shopper", "+1 806 555 0100", "shopper@example.test", "1 Main St", "Bastrop", "TX", "78602", null,
                "sms", null, null, List.of(new OrderPlaced.Package(shipment, "org-mama", windowId,
                PreorderFulfilment.DOOR, 2799, 799, lines)));
    }

    /** The pick list of one run in a page. */
    private static JsonNode listOf(Res page, UUID runId) {
        for (JsonNode item : page.body().path("items")) {
            if (runId.toString().equals(item.path("runId").asString())) {
                return item;
            }
        }
        throw new AssertionError("No pick list for run " + runId + " in " + page.body());
    }

    private List<String> outboxTypes(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", aggregateId).query(String.class).list();
    }

    private List<String> outboxPayloads(String type, String aggregateId) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at").param("t", type).param("id", aggregateId).query(String.class).list();
    }

    private Res call(HttpMethod method, String uri) {
        return http.method(method).uri(uri).exchange((req, res) -> read(res.getStatusCode().value(),
                res.getBody().readAllBytes()));
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
