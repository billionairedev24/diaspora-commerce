package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.afrotransact.deliveryruns.domain.InvalidTransitionException;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.WindowOutcome;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0005 §4 against real PostgreSQL: the staff-set minimum (default from configuration), the close date passing
 * below it (AWAITING_DECISION, no auto-cancel, a staff event), CANCEL / PROCEED with a required, logged reason,
 * cancelling a window (event with every order, refund progress per order, the run it was handed to) and closing early
 * once the minimum is met. Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false",
    "at.delivery-runs.preorders.default-min-orders=3", "at.delivery-runs.geo.base-url=http://127.0.0.1:1"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class PreorderDecisionIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
    }

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    record Res(int status, JsonNode body) {
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
    private final PreorderOrderRepository orderRepository;
    private final LocationService locations;
    private final RunService runs;
    private final JdbcClient jdbc;

    @Autowired
    PreorderDecisionIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, FakeUpstreams upstreams,
                       PreorderWindowService windows, PreorderDecisionService decisions,
                       PreorderProjectionService projection,
                       PreorderSchedulerService scheduler, PreorderWindowRepository windowRepository,
                       PreorderOrderRepository orderRepository, LocationService locations, RunService runs,
                       JdbcClient jdbc) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.upstreams = upstreams;
        this.windows = windows;
        this.decisions = decisions;
        this.projection = projection;
        this.scheduler = scheduler;
        this.windowRepository = windowRepository;
        this.orderRepository = orderRepository;
        this.locations = locations;
        this.runs = runs;
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

    @Test
    void aNewWindowTakesTheConfiguredDefaultMinimumUnlessStaffSetOne() {
        Instant now = clock.instant();
        Res created = call(HttpMethod.POST, "/v1/preorders", body("Default Min #1", "US/TX/Plainview", now, now.plus(
                Duration.ofDays(5)), null));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("minOrders").asInt()).isEqualTo(3);

        Res set = call(HttpMethod.POST, "/v1/preorders", body("Own Min #2", "US/TX/Plainview", now, now.plus(
                Duration.ofDays(5)), 25));
        assertThat(set.body().path("minOrders").asInt()).isEqualTo(25);
        Res none = call(HttpMethod.POST, "/v1/preorders", body("No Min #3", "US/TX/Plainview", now, now.plus(
                Duration.ofDays(5)), 0));
        assertThat(none.body().path("minOrders").asInt()).isZero();

        String id = set.body().path("id").asString();
        Res kept = call(HttpMethod.PUT, "/v1/preorders/" + id, body("Own Min #2 renamed", "US/TX/Plainview", now,
                now.plus(Duration.ofDays(5)), null));
        assertThat(kept.body().path("minOrders").asInt()).as("an edit without a minimum keeps it").isEqualTo(25);
        Res changed = call(HttpMethod.PUT, "/v1/preorders/" + id, body("Own Min #2 renamed", "US/TX/Plainview", now,
                now.plus(Duration.ofDays(5)), 40));
        assertThat(changed.body().path("minOrders").asInt()).isEqualTo(40);

        Res list = call(HttpMethod.GET, "/v1/preorders?size=100", null);
        assertThat(list.body().path("defaultMinOrders").asInt()).isEqualTo(3);
        assertThat(call(HttpMethod.POST, "/v1/preorders", body("Bad", "US/TX/Plainview", now, now.plus(
                Duration.ofDays(5)), -1)).status()).isEqualTo(400);
    }

    @Test
    void closingBelowTheMinimumWaitsForStaffAndNeverAutoCancels() {
        Fixture f = fixture("Awaiting #1", 3, 2);

        clock.set(f.closes.plusSeconds(60));
        assertThat(scheduler.process(f.window.id())).as("awaiting decision").isEqualTo(1);
        PreorderWindow w = windowRepository.findById(f.window.id()).orElseThrow();
        assertThat(w.outcome()).isEqualTo(WindowOutcome.AWAITING_DECISION);
        assertThat(w.status(clock.instant())).isEqualTo(PreorderStatus.AWAITING_DECISION);
        assertThat(w.runId()).as("nothing handed to a run").isNull();
        assertThat(scheduler.candidates(100)).as("nothing more for the scheduler to do").doesNotContain(w.id());
        assertThat(scheduler.process(w.id())).isZero();

        // The staff event carries the window, the counts and the system's note.
        List<String> needed = outboxPayloads("preorder.window.decision_needed", w.id().toString());
        assertThat(needed).hasSize(1);
        JsonNode event = json.readTree(needed.get(0));
        assertThat(event.path("status").asString()).isEqualTo("AWAITING_DECISION");
        assertThat(event.path("minOrders").asInt()).isEqualTo(3);
        assertThat(event.path("decision").path("orders").asInt()).isEqualTo(2);
        assertThat(event.path("decision").path("kind").asString()).isEqualTo("AWAITING");
        assertThat(outboxTypes(w.id().toString())).doesNotContain("preorder.window.closed",
                "preorder.window.cancelled");

        // Staff see it under the decision tab and cannot edit it meanwhile.
        Res list = call(HttpMethod.GET, "/v1/preorders?status=decision&size=100", null);
        assertThat(list.body().path("items")).extracting(i -> i.path("id").asString()).contains(w.id().toString());
        assertThat(list.body().path("counts").path("decision").asInt()).isPositive();
        Res row = call(HttpMethod.GET, "/v1/preorders/" + w.id(), null);
        assertThat(row.body().path("decisionNeeded").asBoolean()).isTrue();
        assertThat(row.body().path("statusLabel").asString()).isEqualTo("Needs a decision");
        assertThat(call(HttpMethod.PUT, "/v1/preorders/" + w.id(), body("Awaiting #1", "US/TX/Bastrop", f.opens,
                f.closes, 3)).status()).isEqualTo(422);
    }

    @Test
    void metMinimumClosesAndHandsOverWithoutAnyDecision() {
        Fixture f = fixture("Met #1", 2, 2);
        clock.set(f.closes.plusSeconds(60));
        assertThat(scheduler.process(f.window.id())).isEqualTo(1);
        PreorderWindow w = windowRepository.findById(f.window.id()).orElseThrow();
        assertThat(w.outcome()).isNull();
        assertThat(w.runId()).isNotNull();
        assertThat(outboxTypes(w.id().toString())).contains("preorder.window.closed")
                .doesNotContain("preorder.window.decision_needed");
    }

    @Test
    void proceedingNeedsAReasonIsLoggedAndHandsTheOrdersToARun() {
        Fixture f = fixture("Proceed #1", 3, 2);
        clock.set(f.closes.plusSeconds(60));
        scheduler.process(f.window.id());
        String id = f.window.id().toString();

        Res blank = call(HttpMethod.POST, "/v1/preorders/" + id + "/decision", Map.of("decision", "PROCEED",
                "reason", " "));
        assertThat(blank.status()).isIn(400, 422);
        assertThat(windowRepository.findById(f.window.id()).orElseThrow().outcome())
                .isEqualTo(WindowOutcome.AWAITING_DECISION);

        Res proceed = call(HttpMethod.POST, "/v1/preorders/" + id + "/decision", Map.of("decision", "PROCEED",
                "reason", "Two orders still cover the drive"));
        assertThat(proceed.status()).isEqualTo(200);
        assertThat(proceed.body().path("outcome").asString()).isEqualTo("PROCEEDED");
        assertThat(proceed.body().path("status").asString()).isEqualTo("CLOSED");

        clock.set(clock.instant().plusSeconds(60));
        assertThat(scheduler.candidates(100)).contains(f.window.id());
        assertThat(scheduler.process(f.window.id())).isPositive();
        PreorderWindow w = windowRepository.findById(f.window.id()).orElseThrow();
        assertThat(w.runId()).isNotNull();
        Run run = runs.get(w.runId());
        assertThat(run.status()).isEqualTo(RunStatus.CONFIRMED);
        assertThat(run.reservedOrders()).isEqualTo(2);

        Res log = call(HttpMethod.GET, "/v1/preorders/" + id + "/decisions", null);
        assertThat(log.body().path("items")).extracting(i -> i.path("kind").asString())
                .containsExactly("AWAITING", "PROCEED");
        JsonNode entry = log.body().path("items").get(1);
        assertThat(entry.path("reason").asString()).isEqualTo("Two orders still cover the drive");
        assertThat(entry.path("actor").asString()).isEqualTo("local-dev");
        assertThat(entry.path("at").asString()).isNotBlank();
        assertThat(entry.path("orders").asInt()).isEqualTo(2);
        assertThat(entry.path("minimum").asInt()).isEqualTo(3);
        assertThat(outboxPayloads("preorder.window.decided", id)).hasSize(1);

        assertThat(call(HttpMethod.POST, "/v1/preorders/" + id + "/decision", Map.of("decision", "CANCEL",
                "reason", "changed my mind")).status()).as("decided once").isEqualTo(409);
    }

    @Test
    void cancellingAWaitingWindowRefundsEveryOrderAndShowsProgressPerOrder() {
        Fixture f = fixture("Cancel #1", 5, 3);
        clock.set(f.closes.plusSeconds(60));
        scheduler.process(f.window.id());
        String id = f.window.id().toString();

        Res cancelled = call(HttpMethod.POST, "/v1/preorders/" + id + "/decision", Map.of("decision", "CANCEL",
                "reason", "Not enough orders to make the trip"));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().path("outcome").asString()).isEqualTo("CANCELLED");

        // The event orders-service refunds from: every order of the window, with the reason and who decided.
        List<String> events = outboxPayloads("preorder.window.cancelled", id);
        assertThat(events).hasSize(1);
        JsonNode event = json.readTree(events.get(0));
        assertThat(event.path("orders")).extracting(o -> o.path("orderId").asString())
                .containsExactlyInAnyOrderElementsOf(f.orderIds);
        assertThat(event.path("decision").path("reason").asString()).isEqualTo("Not enough orders to make the trip");
        assertThat(event.path("decision").path("actor").asString()).isEqualTo("local-dev");
        assertThat(event.path("windowId").asString()).isEqualTo(id);

        // Each order starts with a pending refund; orders-service then reports each one.
        Res orders = call(HttpMethod.GET, "/v1/preorders/" + id + "/orders?size=50", null);
        assertThat(orders.body().path("items")).extracting(o -> o.path("refundStatus").asString())
                .containsOnly("PENDING");
        assertThat(call(HttpMethod.GET, "/v1/preorders/" + id + "/refunds", null).body().path("pending").asInt())
                .isEqualTo(3);

        projection.onRefund(UUID.randomUUID(), f.orderIds.get(0), RefundStatus.REFUNDED, 2799L, null, clock.instant());
        projection.onRefund(UUID.randomUUID(), f.orderIds.get(1), RefundStatus.VOIDED, 2799L, null, clock.instant());
        projection.onRefund(UUID.randomUUID(), f.orderIds.get(2), RefundStatus.FAILED, null, "card closed",
                clock.instant());
        Res summary = call(HttpMethod.GET, "/v1/preorders/" + id + "/refunds", null);
        assertThat(summary.body().path("refunded").asInt()).isEqualTo(1);
        assertThat(summary.body().path("voided").asInt()).isEqualTo(1);
        assertThat(summary.body().path("failed").asInt()).isEqualTo(1);
        assertThat(summary.body().path("pending").asInt()).isZero();
        assertThat(summary.body().path("done").asBoolean()).isFalse();
        PreorderOrder failed = orderRepository.findById(f.orderIds.get(2)).orElseThrow();
        assertThat(failed.refund().error()).isEqualTo("card closed");

        // A retry that succeeds settles it; a late PENDING never moves a settled refund back.
        projection.onRefund(UUID.randomUUID(), f.orderIds.get(2), RefundStatus.REFUNDED, 2799L, null, clock.instant());
        projection.onRefund(UUID.randomUUID(), f.orderIds.get(2), RefundStatus.FAILED, null, "stale", clock.instant());
        assertThat(orderRepository.findById(f.orderIds.get(2)).orElseThrow().refund().status())
                .isEqualTo(RefundStatus.REFUNDED);
        assertThat(call(HttpMethod.GET, "/v1/preorders/" + id + "/refunds", null).body().path("done").asBoolean())
                .isTrue();

        // Repeats are harmless: the same event id is applied once, and the window cannot be cancelled twice.
        UUID same = UUID.randomUUID();
        projection.onRefund(same, f.orderIds.get(0), RefundStatus.VOIDED, 1L, null, clock.instant());
        projection.onRefund(same, f.orderIds.get(0), RefundStatus.FAILED, null, "dup", clock.instant());
        assertThatThrownBy(() -> decisions.cancel(f.window.id(), "again", null))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(outboxPayloads("preorder.window.cancelled", id)).hasSize(1);
        assertThat(scheduler.candidates(100)).doesNotContain(f.window.id());
        assertThat(scheduler.process(f.window.id())).isZero();
    }

    @Test
    void cancellingAnOpenWindowNeedsAReasonAndAlsoCancelsItsRun() {
        Fixture f = fixture("Cancel #2", 1, 2);
        clock.set(f.closes.plusSeconds(60));
        scheduler.process(f.window.id());
        PreorderWindow handed = windowRepository.findById(f.window.id()).orElseThrow();
        Run run = runs.get(handed.runId());
        assertThat(run.status()).isEqualTo(RunStatus.CONFIRMED);

        assertThatThrownBy(() -> decisions.cancel(f.window.id(), " ", null)).isInstanceOf(RuleViolation.class);
        WindowView cancelled = decisions.cancel(f.window.id(), "Seller stock fell through", null);
        assertThat(cancelled.status()).isEqualTo(PreorderStatus.CANCELLED);
        assertThat(runs.get(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(outboxTypes(run.id().toString())).contains("delivery-runs.run.cancelled");
        assertThat(outboxPayloads("preorder.window.cancelled", f.window.id().toString())).hasSize(1);
    }

    @Test
    void anOpenWindowClosesEarlyOnlyOnceItsMinimumIsMet() {
        Instant now = clock.instant();
        PreorderWindow w = publish("Early #1", "US/TX/Bastrop", now.minus(Duration.ofDays(1)), now.plus(
                Duration.ofDays(4)), 2);
        String id = w.id().toString();
        projection.onOrderPlaced(UUID.randomUUID(), placed(UUID.randomUUID().toString(), w.id(), now));

        Res early = call(HttpMethod.POST, "/v1/preorders/" + id + "/close-early", Map.of("reason", "Sold out"));
        assertThat(early.status()).isEqualTo(422);
        assertThat(early.body().path("code").asString()).isEqualTo("delivery-runs.preorder-minimum-not-met");
        Res row = call(HttpMethod.GET, "/v1/preorders/" + id, null);
        assertThat(row.body().path("canCloseEarly").asBoolean()).isFalse();

        projection.onOrderPlaced(UUID.randomUUID(), placed(UUID.randomUUID().toString(), w.id(), now));
        row = call(HttpMethod.GET, "/v1/preorders/" + id, null);
        assertThat(row.body().path("minimumMet").asBoolean()).isTrue();
        assertThat(row.body().path("canCloseEarly").asBoolean()).isTrue();

        assertThat(call(HttpMethod.POST, "/v1/preorders/" + id + "/close-early", Map.of("reason", "")).status())
                .isIn(400, 422);
        Res closed = call(HttpMethod.POST, "/v1/preorders/" + id + "/close-early", Map.of("reason",
                "Both seller lots are gone"));
        assertThat(closed.status()).isEqualTo(200);
        clock.set(clock.instant().plusSeconds(120));
        assertThat(windowRepository.findById(w.id()).orElseThrow().status(clock.instant()))
                .isEqualTo(PreorderStatus.CLOSED);
        assertThat(scheduler.process(w.id())).isPositive();
        PreorderWindow done = windowRepository.findById(w.id()).orElseThrow();
        assertThat(done.runId()).isNotNull();
        assertThat(done.outcome()).isNull();
        Res log = call(HttpMethod.GET, "/v1/preorders/" + id + "/decisions", null);
        assertThat(log.body().path("items")).extracting(i -> i.path("kind").asString()).containsExactly("EARLY_CLOSE");
        assertThat(log.body().path("items").get(0).path("reason").asString()).isEqualTo("Both seller lots are gone");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** A published Bastrop window that is open, with {@code orders} projected orders and a location to hand them to. */
    private Fixture fixture(String name, int minimum, int orders) {
        locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null, CutoffPolicy.CANCEL));
        Instant now = clock.instant();
        Instant opens = now.minus(Duration.ofDays(2));
        Instant closes = now.plus(Duration.ofDays(3));
        PreorderWindow w = publish(name, "US/TX/Bastrop", opens, closes, minimum);
        assertThat(scheduler.process(w.id())).as("opened and announced").isEqualTo(2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String order = UUID.randomUUID().toString();
            ids.add(order);
            projection.onOrderPlaced(UUID.randomUUID(), placed(order, w.id(), now));
        }
        return new Fixture(w, opens, closes, List.copyOf(ids));
    }

    private record Fixture(PreorderWindow window, Instant opens, Instant closes, List<String> orderIds) {
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

    private Map<String, Object> body(String name, String target, Instant opens, Instant closes, Integer minOrders) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("targets", List.of(target));
        b.put("opensAt", opens.toString());
        b.put("closesAt", closes.toString());
        b.put("deliversOn", closes.plus(Duration.ofDays(3)).atZone(CHICAGO).toLocalDate().toString());
        b.put("timeZone", "America/Chicago");
        b.put("ribbon", "Pre-order is open");
        b.put("headline", "Fresh egusi");
        b.put("smsCopy", "AfroTransact: pre-order open. Reply STOP to opt out");
        b.put("categories", List.of("Pantry"));
        b.put("door", true);
        b.put("pickup", false);
        if (minOrders != null) {
            b.put("minOrders", minOrders);
        }
        return b;
    }

    private List<String> outboxTypes(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", aggregateId).query(String.class).list();
    }

    private List<String> outboxPayloads(String type, String aggregateId) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at").param("t", type).param("id", aggregateId).query(String.class).list();
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
