package com.afrotransact.deliveryruns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.AvailableRun;
import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.PublicRunService;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunCommand;
import com.afrotransact.deliveryruns.application.RunService;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.application.SlotReservation;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S323 early access against PostgreSQL: a member whose tier has early access sees, and can order from, an announced run
 * some hours before it opens to everyone. The hours come from orders-service (the member's entitlements); the run
 * enforces them when it takes the slot.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.delivery-runs.public-web-url=http://storefront.test", "at.delivery-runs.jobs.interval=1h",
    "at.delivery-runs.jobs.initial-delay=1h"})
@ActiveProfiles("local")
@Import(ItSupport.class)
class EarlyAccessIT {

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

    private final LocationService locations;
    private final RunService runs;
    private final RunSlotService slots;
    private final PublicRunService publicRuns;
    private final MutableClock clock;

    @Autowired
    EarlyAccessIT(LocationService locations, RunService runs, RunSlotService slots, PublicRunService publicRuns,
                  MutableClock clock) {
        this.locations = locations;
        this.runs = runs;
        this.slots = slots;
        this.publicRuns = publicRuns;
        this.clock = clock;
    }

    @BeforeEach
    void startOfDay() {
        clock.set(Instant.parse("2026-10-19T15:00:00Z"));
    }

    @AfterEach
    void resetActor() {
        ItSupport.reset();
    }

    /** An announced run that opens to everyone in ten hours. */
    private Run announcedRun(Location location) {
        Instant now = clock.instant();
        Instant cutoff = now.plus(Duration.ofDays(4));
        Instant start = cutoff.plus(Duration.ofDays(2));
        Run draft = runs.create(new RunCommand(location.id(), start, start.plus(Duration.ofHours(4)), cutoff,
                now.plus(Duration.ofHours(10)), cutoff.plus(Duration.ofHours(12)), null, null, null, null, null, null,
                null, null, null, null));
        return runs.announce(draft.id());
    }

    private boolean listed(Run run, int earlyAccessHours) {
        return publicRuns.available(null, 30.12, -97.32, earlyAccessHours).stream().map(AvailableRun::run)
                .anyMatch(r -> r.id().equals(run.id()));
    }

    @Test
    void aMemberSeesAndOrdersFromAnAnnouncedRunBeforeItOpensToEveryone() {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
        Run run = announcedRun(bastrop);
        assertThat(run.status()).isEqualTo(RunStatus.ANNOUNCED);

        // everyone else: not listed, not orderable
        assertThat(listed(run, 0)).isFalse();
        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(run.id(), "ord-pub", "cust-1", 1, false)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("delivery-runs.run-closed");

        // a member whose early access is shorter than the wait: the same
        assertThat(listed(run, 5)).isFalse();
        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(run.id(), "ord-short", "cust-2", 1, false, 5)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("delivery-runs.run-closed");

        // 24 hours of early access cover the ten hours to go
        assertThat(listed(run, 24)).isTrue();
        SlotReservation slot = slots.reserve(new ReserveCommand(run.id(), "ord-member", "cust-3", 1, false, 24));
        assertThat(slot.created()).isTrue();
        assertThat(slot.run().id()).isEqualTo(run.id());
        assertThat(runs.get(run.id()).reservedOrders()).isEqualTo(1);
        // a replay of the same order is the same slot
        assertThat(slots.reserve(new ReserveCommand(run.id(), "ord-member", "cust-3", 1, false, 24)).created())
                .isFalse();

        // once it opens everyone can order from it, and the member's slot is still theirs
        clock.advance(Duration.ofHours(11));
        runs.open(run.id());
        assertThat(listed(run, 0)).isTrue();
        assertThat(slots.reserve(new ReserveCommand(run.id(), "ord-after", "cust-4", 1, false)).created()).isTrue();
        assertThat(runs.get(run.id()).reservedOrders()).isEqualTo(2);
    }

    @Test
    void earlyAccessNeverOpensARunPastItsCutoff() {
        Location bastrop = locations.create(Fixtures.bastrop(Fixtures.slug("bastrop"), DropMode.DOORSTEP, null,
                CutoffPolicy.EXTEND));
        Run run = announcedRun(bastrop);
        clock.set(run.terms().cutoffAt().plusSeconds(60));
        assertThat(listed(run, 720)).isFalse();
        UUID id = run.id();
        assertThatThrownBy(() -> slots.reserve(new ReserveCommand(id, "ord-late", "cust-5", 1, false, 720)))
                .isInstanceOf(ConflictException.class);
    }
}
