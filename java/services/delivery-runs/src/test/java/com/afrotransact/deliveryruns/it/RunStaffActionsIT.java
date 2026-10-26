package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunSchedulerService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
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
 * S260 against real PostgreSQL, through the HTTP API staff use: a run is scheduled with notes and the location's fee
 * rule, edited before it opens, extended, confirmed below its minimum (a reason is required and logged), and cancelled
 * (a reason is required; every slot is cancelled and the event lists the orders and customers so orders-service
 * refunds and releases stock). The cutoff job enforces minimum and capacity and logs its decisions as {@code system}.
 * Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.enabled=false"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class RunStaffActionsIT {

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
    private final RunSlotService slots;
    private final RunSchedulerService scheduler;
    private final JdbcClient jdbc;

    @Autowired
    RunStaffActionsIT(@LocalServerPort int port, JsonMapper json, MutableClock clock, LocationService locations,
                      RunService runs, RunSlotService slots, RunSchedulerService scheduler, JdbcClient jdbc) {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.json = json;
        this.clock = clock;
        this.locations = locations;
        this.runs = runs;
        this.slots = slots;
        this.scheduler = scheduler;
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

    private Map<String, Object> body(UUID location, Duration cutoffIn) {
        Instant cutoff = clock.instant().plus(cutoffIn);
        Instant start = cutoff.plus(Duration.ofDays(2));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("locationId", location.toString());
        b.put("windowStart", start.toString());
        b.put("windowEnd", start.plus(Duration.ofHours(4)).toString());
        b.put("cutoffAt", cutoff.toString());
        b.put("minOrders", 2);
        return b;
    }

    @Test
    void staffScheduleEditOpenExtendAndConfirmBelowTheMinimumWithALoggedReason() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Map<String, Object> create = body(location, Duration.ofDays(5));
        create.put("notes", "  Bring the cold bags  ");
        create.put("capacityOrders", 4);
        Res created = call(HttpMethod.POST, "/v1/runs", create);
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        assertThat(created.body().path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.body().path("notes").asString()).isEqualTo("Bring the cold bags");
        assertThat(created.body().path("capacityOrders").asInt()).isEqualTo(4);
        assertThat(created.body().path("feeMinor").asLong()).as("the fee rule / location default prices it")
                .isEqualTo(799);
        assertThat(created.body().path("breakEvenOrders").isNumber()).isTrue();

        Map<String, Object> tooLong = body(location, Duration.ofDays(5));
        tooLong.put("notes", "x".repeat(501));
        assertThat(call(HttpMethod.POST, "/v1/runs", tooLong).status()).isEqualTo(400);

        Map<String, Object> edit = body(location, Duration.ofDays(6));
        edit.put("minOrders", 3);
        edit.put("feeMinor", 999);
        edit.put("notes", "");
        Res edited = call(HttpMethod.PUT, "/v1/runs/" + id, edit);
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().path("minOrders").asInt()).isEqualTo(3);
        assertThat(edited.body().path("feeMinor").asLong()).isEqualTo(999);
        assertThat(edited.body().path("notes").isNull()).as("a blank note clears it").isTrue();

        assertThat(call(HttpMethod.POST, "/v1/runs/" + id + "/announce", null).body().path("status").asString())
                .isEqualTo("ANNOUNCED");
        assertThat(call(HttpMethod.POST, "/v1/runs/" + id + "/open", null).body().path("status").asString())
                .isEqualTo("OPEN");
        UUID runId = UUID.fromString(id);
        reserve(runId, "ord-s1", "cust-s1");

        Res extend = call(HttpMethod.POST, "/v1/runs/" + id + "/extend",
                Map.of("cutoffAt", clock.instant().plus(Duration.ofDays(8)).toString(), "reason", "Seller delayed"));
        assertThat(extend.status()).isEqualTo(200);
        assertThat(extend.body().path("status").asString()).isEqualTo("EXTENDED");
        assertThat(extend.body().path("extensions").asInt()).isEqualTo(1);
        assertThat(call(HttpMethod.POST, "/v1/runs/" + id + "/extend",
                Map.of("cutoffAt", clock.instant().plus(Duration.ofDays(1)).toString())).status())
                .as("the cutoff can only move later").isEqualTo(422);

        Res noReason = call(HttpMethod.POST, "/v1/runs/" + id + "/confirm", null);
        assertThat(noReason.status()).as("1 of 3 orders: overriding the minimum needs a reason").isEqualTo(422);
        assertThat(noReason.body().path("code").asString()).isEqualTo("delivery-runs.reason-required");
        assertThat(runs.get(runId).status()).isEqualTo(RunStatus.EXTENDED);

        Res confirmed = call(HttpMethod.POST, "/v1/runs/" + id + "/confirm",
                Map.of("reason", "The sellers already packed"));
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(runs.orders(runId, RunOrderStatus.CONFIRMED)).extracting(RunOrder::orderId)
                .containsExactly("ord-s1");

        JsonNode log = call(HttpMethod.GET, "/v1/runs/" + id + "/log", null).body();
        assertThat(log).hasSize(2);
        assertThat(log.get(0).path("kind").asString()).isEqualTo("EXTEND");
        assertThat(log.get(0).path("reason").asString()).isEqualTo("Seller delayed");
        assertThat(log.get(0).path("actor").asString()).isEqualTo("local-dev");
        assertThat(log.get(0).path("fromCutoff").isString()).isTrue();
        assertThat(log.get(0).path("toCutoff").isString()).isTrue();
        assertThat(log.get(1).path("kind").asString()).isEqualTo("CONFIRM_OVERRIDE");
        assertThat(log.get(1).path("reason").asString()).isEqualTo("The sellers already packed");
        assertThat(log.get(1).path("orders").asInt()).isEqualTo(1);
        assertThat(log.get(1).path("minimum").asInt()).isEqualTo(3);
        assertThat(log.get(1).path("fromStatus").asString()).isEqualTo("EXTENDED");
        assertThat(log.get(1).path("toStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(types(runId)).contains("delivery-runs.run.extended", "delivery-runs.run.confirmed");
    }

    @Test
    void confirmingAtTheMinimumNeedsNoReasonAndIsLoggedAsAPlainConfirmation() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Run open = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(2), 2)).id());
        reserve(open.id(), "ord-m1", "cust-1");
        reserve(open.id(), "ord-m2", "cust-2");

        assertThat(call(HttpMethod.POST, "/v1/runs/" + open.id() + "/confirm", null).status()).isEqualTo(200);
        JsonNode log = call(HttpMethod.GET, "/v1/runs/" + open.id() + "/log", null).body();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).path("kind").asString()).isEqualTo("CONFIRM");
        assertThat(log.get(0).path("reason").isNull()).isTrue();
    }

    @Test
    void cancellingNeedsAReasonCancelsEverySlotAndTellsOrdersWhoToRefund() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND)).id();
        Run open = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(3), 3)).id());
        reserve(open.id(), "ord-x1", "cust-x1");
        reserve(open.id(), "ord-x2", "cust-x2");
        String uri = "/v1/runs/" + open.id() + "/cancel";

        assertThat(call(HttpMethod.POST, uri, Map.of("reason", "  ")).status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, uri, Map.of()).status()).isEqualTo(400);
        assertThat(runs.get(open.id()).status()).isEqualTo(RunStatus.OPEN);

        Res cancelled = call(HttpMethod.POST, uri, Map.of("reason", "Tropical storm warning"));
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().path("cancelReason").asString()).isEqualTo("Tropical storm warning");
        assertThat(runs.orders(open.id(), RunOrderStatus.CANCELLED)).extracting(RunOrder::orderId)
                .containsExactlyInAnyOrder("ord-x1", "ord-x2");
        String payload = payload("delivery-runs.run.cancelled", open.id());
        assertThat(payload).contains("\"reason\": \"Tropical storm warning\"", "\"orderId\": \"ord-x1\"",
                "\"customerId\": \"cust-x1\"", "\"orderId\": \"ord-x2\"", "\"customerId\": \"cust-x2\"");

        JsonNode log = call(HttpMethod.GET, "/v1/runs/" + open.id() + "/log", null).body();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).path("kind").asString()).isEqualTo("CANCEL");
        assertThat(log.get(0).path("reason").asString()).isEqualTo("Tropical storm warning");
        assertThat(log.get(0).path("actor").asString()).isEqualTo("local-dev");
        assertThat(log.get(0).path("orders").asInt()).isEqualTo(2);

        assertThat(call(HttpMethod.POST, uri, Map.of("reason", "Again")).status()).as("cancelled stays cancelled")
                .isEqualTo(409);
    }

    @Test
    void theCutoffJobEnforcesTheMinimumAndLogsItsDecisionsAsSystem() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND)).id();
        Run enough = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(1), 2)).id());
        Run short1 = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(1), 3)).id());
        reserve(enough.id(), "ord-j1", "cust-1");
        reserve(enough.id(), "ord-j2", "cust-2");
        reserve(short1.id(), "ord-j3", "cust-3");

        clock.advance(Duration.ofDays(1).plusMinutes(1));
        assertThat(scheduler.processCutoff(enough.id())).isTrue();
        assertThat(scheduler.processCutoff(short1.id())).isTrue();
        assertThat(runs.get(enough.id()).status()).isEqualTo(RunStatus.CONFIRMED);
        assertThat(runs.get(short1.id()).status()).as("below the minimum: the policy extends once")
                .isEqualTo(RunStatus.EXTENDED);

        clock.set(runs.get(short1.id()).terms().cutoffAt().plusSeconds(1));
        assertThat(scheduler.processCutoff(short1.id())).isTrue();
        assertThat(runs.get(short1.id()).status()).as("still short after the one extension: cancelled")
                .isEqualTo(RunStatus.CANCELLED);

        JsonNode confirmedLog = call(HttpMethod.GET, "/v1/runs/" + enough.id() + "/log", null).body();
        assertThat(confirmedLog).hasSize(1);
        assertThat(confirmedLog.get(0).path("kind").asString()).isEqualTo("CONFIRM");
        assertThat(confirmedLog.get(0).path("actor").asString()).isEqualTo("system");
        JsonNode shortLog = call(HttpMethod.GET, "/v1/runs/" + short1.id() + "/log", null).body();
        assertThat(shortLog).extracting(n -> n.path("kind").asString()).containsExactly("EXTEND", "CANCEL");
        assertThat(shortLog).extracting(n -> n.path("actor").asString()).containsOnly("system");
        assertThat(shortLog.get(1).path("reason").asString()).isEqualTo("MINIMUM_NOT_MET");
        assertThat(runs.orders(short1.id(), RunOrderStatus.CANCELLED)).extracting(RunOrder::orderId)
                .containsExactly("ord-j3");
    }

    @Test
    void capacityIsEnforcedAtReservationAndAfterTheCutoff() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, 2,
                CutoffPolicy.CANCEL)).id();
        Run open = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(2), 1)).id());
        reserve(open.id(), "ord-k1", "cust-1");
        reserve(open.id(), "ord-k2", "cust-2");
        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(open.id(), "ord-k3", "cust-3", 1, false)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("delivery-runs.run-full");
        assertThat(runs.get(open.id()).reservedOrders()).isEqualTo(2);

        Map<String, Object> lower = body(location, Duration.ofDays(2));
        lower.put("capacityOrders", 1);
        lower.put("feeMinor", runs.get(open.id()).terms().feeMinor());
        lower.put("cutoffAt", runs.get(open.id()).terms().cutoffAt().toString());
        lower.put("windowStart", runs.get(open.id()).terms().window().start().toString());
        lower.put("windowEnd", runs.get(open.id()).terms().window().end().toString());
        assertThat(call(HttpMethod.PUT, "/v1/runs/" + open.id(), lower).status())
                .as("capacity never drops below what is reserved").isEqualTo(422);

        clock.set(open.terms().cutoffAt().plusSeconds(1));
        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(open.id(), "ord-k4", "cust-4", 1, false)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("delivery-runs.run-closed");
    }

    @Test
    void onlyStaffWithTheRightPermissionsActOnRuns() {
        UUID location = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL)).id();
        Run open = runs.open(runs.create(Fixtures.run(location, clock.instant(), Duration.ofDays(2), 2)).id());
        ItSupport.as(new Actor("reader", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("delivery-runs:read")));
        assertThat(call(HttpMethod.GET, "/v1/runs/" + open.id() + "/log", null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/v1/runs/" + open.id() + "/cancel", Map.of("reason", "x")).status())
                .isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/v1/runs/" + open.id() + "/confirm", Map.of("reason", "x")).status())
                .isEqualTo(403);
        assertThatThrownBy(() -> runs.confirm(open.id(), "x")).isInstanceOf(ForbiddenActionException.class);
        ItSupport.as(new Actor("shopper", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        assertThat(call(HttpMethod.GET, "/v1/runs/" + open.id() + "/log", null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/v1/runs/" + UUID.randomUUID() + "/log", null).status()).isEqualTo(403);
        ItSupport.reset();
        assertThat(call(HttpMethod.GET, "/v1/runs/" + UUID.randomUUID() + "/log", null).status()).isEqualTo(404);
        List<RunOrder> none = runs.orders(open.id(), null);
        assertThat(none).isEmpty();
    }

    private void reserve(UUID runId, String orderId, String customerId) {
        slots.reserve(new ReserveCommand(runId, orderId, customerId, 1, true));
    }

    private List<String> types(UUID runId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", runId.toString()).query(String.class).list();
    }

    private String payload(String type, UUID runId) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at desc limit 1")
                .param("t", type).param("id", runId.toString()).query(String.class).single();
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
