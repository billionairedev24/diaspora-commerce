package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.afrotransact.deliveryruns.adapter.in.scheduling.RunScheduleJob;
import com.afrotransact.deliveryruns.application.AvailableRun;
import com.afrotransact.deliveryruns.application.DispatchList;
import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.PublicRunService;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunSchedulerService;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.application.SlotReservation;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Run lifecycle against real PostgreSQL: slots and capacity roll-over, the cutoff job (reminder, confirm, extend,
 * cancel), its safety across concurrent replicas, and the outbox events with what notifications needs.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.interval=1h",
    "at.delivery-runs.jobs.initial-delay=1h"})
@ActiveProfiles("local")
@Import(ItSupport.class)
@Testcontainers
class RunFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private final LocationService locations;
    private final RunService runs;
    private final RunSlotService slots;
    private final RunSchedulerService scheduler;
    private final RunScheduleJob job;
    private final PublicRunService publicRuns;
    private final MutableClock clock;
    private final FakeGeo geo;
    private final JdbcClient jdbc;

    @Autowired
    RunFlowIT(LocationService locations, RunService runs, RunSlotService slots, RunSchedulerService scheduler,
              RunScheduleJob job, PublicRunService publicRuns, MutableClock clock, FakeGeo geo, JdbcClient jdbc) {
        this.locations = locations;
        this.runs = runs;
        this.slots = slots;
        this.scheduler = scheduler;
        this.job = job;
        this.publicRuns = publicRuns;
        this.clock = clock;
        this.geo = geo;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void startOfDay() {
        clock.set(Instant.parse("2026-10-19T15:00:00Z"));
    }

    @AfterEach
    void resetActor() {
        ItSupport.reset();
    }

    @Test
    void cutoffJobConfirmsARunThatMetItsMinimumAndPublishesEverything() {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.PICKUP_POINT, 10,
                CutoffPolicy.EXTEND));
        Run draft = runs.create(Fixtures.run(bastrop.id(), clock.instant(), Duration.ofDays(4)));
        assertThat(draft.status()).isEqualTo(RunStatus.DRAFT);
        assertThat(draft.estimate().roadMiles()).isEqualTo(32);
        assertThat(draft.terms().feeMinor()).isEqualTo(799);
        assertThat(draft.terms().dropMode()).isEqualTo(DropMode.PICKUP_POINT);
        Run open = runs.open(draft.id());

        reserve(open.id(), "ord-a1", "cust-1");
        reserve(open.id(), "ord-a2", "cust-2");

        clock.set(open.terms().cutoffAt().minus(Duration.ofHours(23)));
        job.tick();
        job.tick();
        assertThat(types(open.id())).containsExactly("delivery-runs.run.opened", "delivery-runs.run.cutoff_reminder");

        clock.set(open.terms().cutoffAt().plusSeconds(30));
        job.tick();
        Run confirmed = runs.get(open.id());
        assertThat(confirmed.status()).isEqualTo(RunStatus.CONFIRMED);
        assertThat(confirmed.milestones().cutOffAt()).isEqualTo(clock.instant());
        List<RunOrder> orders = runs.orders(open.id(), RunOrderStatus.CONFIRMED);
        assertThat(orders).extracting(RunOrder::orderId).containsExactlyInAnyOrder("ord-a1", "ord-a2");
        assertThat(orders).allSatisfy(o -> assertThat(o.handoverCode()).matches("\\d{6}"));
        assertThat(types(open.id())).containsExactly("delivery-runs.run.opened", "delivery-runs.run.cutoff_reminder",
                "delivery-runs.run.confirmed");

        String payload = payload("delivery-runs.run.confirmed", open.id());
        assertThat(payload).contains("\"status\": \"CONFIRMED\"", "\"previousStatus\": \"OPEN\"",
                "\"town\": \"Bastrop\"", "\"feeMinor\": 799", "\"feeDisplay\": \"$7.99\"",
                "\"whatsappInviteUrl\": \"https://chat.whatsapp.com/BastropRuns\"",
                "\"runUrl\": \"http://storefront.test/delivery-runs/" + bastrop.slug() + "?run=" + open.id() + "\"",
                "utm_medium=whatsapp", "utm_content=confirmed", "\"orderId\": \"ord-a1\"",
                "\"customerId\": \"cust-2\"", "\"handoverCode\"", "\"dropMode\": \"PICKUP_POINT\"",
                "Bastrop Public Library", "\"timeZone\": \"America/Chicago\"");

        DispatchList dispatch = slots.dispatch(open.id());
        assertThat(dispatch.orders()).hasSize(2);
        assertThat(dispatch.run().terms().sellerPickupAt()).isNotNull();
        assertThat(slots.dispatchable()).contains(open.id());

        clock.advance(Duration.ofHours(1));
        runs.startCollecting(open.id());
        clock.advance(Duration.ofHours(1));
        runs.outForDelivery(open.id());
        assertThatThrownBy(() -> slots.release(open.id(), "ord-a1")).hasMessageContaining("out for delivery");
        clock.advance(Duration.ofHours(1));
        runs.complete(open.id());
        assertThat(types(open.id())).endsWith("delivery-runs.run.out_for_delivery", "delivery-runs.run.completed");
        assertThat(payload("delivery-runs.run.out_for_delivery", open.id())).contains("\"orderId\": \"ord-a2\"");
    }

    @Test
    void aShortRunIsExtendedOnceThenCancelledWithItsOrdersAndCustomers() {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
        Run open = runs.open(runs.create(Fixtures.run(bastrop.id(), clock.instant(), Duration.ofDays(2), 3)).id());
        reserve(open.id(), "ord-b1", "cust-9");
        Instant firstCutoff = open.terms().cutoffAt();

        clock.set(firstCutoff.plusSeconds(1));
        assertThat(scheduler.processCutoff(open.id())).isTrue();
        Run extended = runs.get(open.id());
        assertThat(extended.status()).isEqualTo(RunStatus.EXTENDED);
        assertThat(extended.extensions()).isEqualTo(1);
        assertThat(extended.terms().cutoffAt()).isEqualTo(firstCutoff.plus(Duration.ofDays(7)));
        assertThat(payload("delivery-runs.run.extended", open.id())).contains("\"reason\": \"MINIMUM_NOT_MET\"",
                "\"previousCutoffAt\": \"" + firstCutoff + "\"");
        assertThat(scheduler.processCutoff(open.id())).as("not due again until the new cutoff").isFalse();

        reserve(open.id(), "ord-b2", "cust-10");
        reserve(open.id(), "ord-b3", "cust-11");
        slots.release(open.id(), "ord-b3");
        assertThat(slots.release(open.id(), "ord-b3").status()).as("release is idempotent")
                .isEqualTo(RunOrderStatus.RELEASED);

        clock.set(extended.terms().cutoffAt().plusSeconds(1));
        job.tick();
        Run cancelled = runs.get(open.id());
        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("MINIMUM_NOT_MET");
        assertThat(runs.orders(open.id(), RunOrderStatus.CANCELLED)).extracting(RunOrder::orderId)
                .containsExactly("ord-b1", "ord-b2");
        String payload = payload("delivery-runs.run.cancelled", open.id());
        assertThat(payload).contains("\"reason\": \"MINIMUM_NOT_MET\"", "\"orderId\": \"ord-b1\"",
                "\"customerId\": \"cust-9\"", "\"orderId\": \"ord-b2\"", "\"customerId\": \"cust-10\"")
                .doesNotContain("ord-b3");
    }

    @Test
    void aFullRunSendsNewPreordersToTheNextRun() {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, 2,
                CutoffPolicy.CANCEL));
        Run first = runs.open(runs.create(Fixtures.run(bastrop.id(), clock.instant(), Duration.ofDays(3))).id());
        Run next = runs.open(runs.create(Fixtures.run(bastrop.id(), clock.instant(), Duration.ofDays(10))).id());

        reserve(first.id(), "ord-c1", "cust-1");
        reserve(first.id(), "ord-c2", "cust-2");
        SlotReservation moved = reserve(first.id(), "ord-c3", "cust-3");
        assertThat(moved.moved()).isTrue();
        assertThat(moved.run().id()).isEqualTo(next.id());
        assertThat(moved.slot().requestedRunId()).isEqualTo(first.id());

        SlotReservation again = reserve(first.id(), "ord-c3", "cust-3");
        assertThat(again.created()).isFalse();
        assertThat(again.slot().id()).isEqualTo(moved.slot().id());
        assertThat(runs.get(next.id()).reservedOrders()).isEqualTo(1);

        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(first.id(), "ord-c4", "cust-4", 1, false)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("delivery-runs.run-full");

        List<AvailableRun> available = publicRuns.available(null, 30.12, -97.32);
        assertThat(available).filteredOn(a -> a.location().id().equals(bastrop.id()))
                .extracting(a -> a.run().id(), AvailableRun::full)
                .containsExactly(tuple(first.id(), true), tuple(next.id(), false));

        slots.release(first.id(), "ord-c1");
        assertThat(reserve(first.id(), "ord-c5", "cust-5").run().id()).isEqualTo(first.id());

        clock.set(first.terms().cutoffAt().plusSeconds(1));
        SlotReservation late = reserve(first.id(), "ord-c6", "cust-6");
        assertThat(late.run().id()).as("after cutoff, orders go to the next run").isEqualTo(next.id());
    }

    @Test
    void cutoffIsDecidedExactlyOnceAcrossConcurrentReplicas() throws Exception {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Run run = runs.open(runs.create(Fixtures.run(bastrop.id(), clock.instant(), Duration.ofDays(1))).id());
            ids.add(run.id());
            if (i % 2 == 0) {
                reserve(run.id(), "ord-d" + i + "a", "cust-a");
                reserve(run.id(), "ord-d" + i + "b", "cust-b");
            }
        }
        clock.advance(Duration.ofDays(1).plusMinutes(1));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> replicas = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            replicas.add(pool.submit(() -> {
                start.await();
                job.tick();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : replicas) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            List<String> decided = types(id).stream().filter(t -> !t.endsWith(".opened")).toList();
            String expected = i % 2 == 0 ? "delivery-runs.run.confirmed" : "delivery-runs.run.cancelled";
            assertThat(decided).as("run %d", i).containsExactly(expected);
        }
        assertThat(scheduler.dueForCutoff(100)).doesNotContainAnyElementsOf(ids);
    }

    @Test
    void polygonLocationsAreRegisteredInGeoAndMatchedByZoneCoverage() {
        Location elgin = locations.create(Fixtures.elgin(Fixtures.slug("elgin")));
        String zoneId = elgin.details().area().geoZoneId();
        assertThat(zoneId).startsWith("zone-elgin-");
        assertThat(geo.registered()).contains("delivery-runs:" + elgin.slug());
        geo.zone(zoneId, new FakeGeo.Box(30.30, 30.40, -97.42, -97.32));
        Run open = runs.open(runs.create(Fixtures.run(elgin.id(), clock.instant(), Duration.ofDays(5))).id());
        assertThat(open.terms().feeMinor()).isEqualTo(899);

        assertThat(publicRuns.available("78621", null, null)).extracting(a -> a.run().id()).contains(open.id());
        assertThat(publicRuns.available("78701", null, null)).extracting(a -> a.run().id())
                .doesNotContain(open.id());
        assertThat(publicRuns.available("99999", null, null)).isEmpty();
    }

    @Test
    void staffPermissionsAreEnforced() {
        ItSupport.as(new Actor("shopper-1", PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of()));
        assertThatThrownBy(() -> locations.list(null)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(UUID.randomUUID(), "o", "c", 1, true)))
                .isInstanceOf(ForbiddenActionException.class);
        ItSupport.as(new Actor("orders-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("delivery-runs:reserve")));
        assertThatThrownBy(() -> runs.cancel(UUID.randomUUID(), "x")).isInstanceOf(ForbiddenActionException.class);
    }

    private SlotReservation reserve(UUID runId, String orderId, String customerId) {
        return slots.reserve(new ReserveCommand(runId, orderId, customerId, 1, true));
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
}
