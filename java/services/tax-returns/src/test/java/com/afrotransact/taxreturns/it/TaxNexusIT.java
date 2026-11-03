package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.TaxNexusService;
import com.afrotransact.taxreturns.application.TaxNexusService.UpdateNexus;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.NexusRow;
import com.afrotransact.taxreturns.domain.tax.NexusStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The nexus tracker against PostgreSQL: trailing sales per state, thresholds, registration and who may change them. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class TaxNexusIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    /** One Orders order: dress 12,998 + jollof 1,850 + pillow 3,200 shipped to Houston, TX. */
    private static final long ORDER_SALES = 12_998 + 1_850 + 3_200;

    private final TaxNexusService nexus;
    private final OrderIntakeService intake;
    private final TestActors actors;

    @Autowired
    TaxNexusIT(TaxNexusService nexus, OrderIntakeService intake, TestActors actors) {
        this.nexus = nexus;
        this.intake = intake;
        this.actors = actors;
    }

    @BeforeEach
    void asStaff() {
        actors.staff();
    }

    private NexusRow texas() {
        return nexus.state(YearMonth.now(), "US-TX");
    }

    private UpdateNexus set(long threshold, Integer txns, boolean registered) {
        return new UpdateNexus(null, "US-TX", threshold, txns, registered, registered ? "TX-32-1234567" : null,
                registered ? LocalDate.now().minusDays(1) : null, "Confirmed with the tax advisor");
    }

    @Test
    void salesIntoAStateAreMeasuredAgainstItsThresholdsAndRegistrationClearsTheWarning() {
        NexusRow before = texas();
        assertThat(before.salesThresholdMinor()).as("the default until staff set one")
                .isEqualTo(TaxNexusService.DEFAULT_SALES_THRESHOLD_MINOR);
        assertThat(before.customised()).isFalse();

        intake.onOrderPlaced(UUID.randomUUID(), new Orders("cust_" + UUID.randomUUID()).placed(Instant.now()));
        NexusRow after = texas();
        assertThat(after.salesMinor() - before.salesMinor()).isEqualTo(ORDER_SALES);
        assertThat(after.orders() - before.orders()).isEqualTo(1);
        assertThat(after.facilitator()).isTrue();
        assertThat(after.name()).isEqualTo("Texas");

        // a threshold just under today's sales: past it, not registered
        NexusRow exceeded = nexus.update(set(after.salesMinor(), null, false));
        assertThat(exceeded.status()).isEqualTo(NexusStatus.EXCEEDED);
        assertThat(exceeded.percent()).isGreaterThanOrEqualTo(100);
        assertThat(exceeded.customised()).isTrue();
        assertThat(exceeded.updatedBy()).isEqualTo("staff_1");
        assertThat(nexus.tracker(YearMonth.now()).getFirst().status()).as("the one needing action comes first")
                .isEqualTo(NexusStatus.EXCEEDED);

        // 80 %: approaching
        assertThat(nexus.update(set(after.salesMinor() * 100 / 85, null, false)).status())
                .isEqualTo(NexusStatus.APPROACHING);
        // a transaction-count test can trip it on its own
        assertThat(nexus.update(set(after.salesMinor() * 1000, (int) after.orders(), false)).status())
                .isEqualTo(NexusStatus.EXCEEDED);

        NexusRow registered = nexus.update(set(after.salesMinor(), null, true));
        assertThat(registered.status()).isEqualTo(NexusStatus.REGISTERED);
        assertThat(registered.registrationNumber()).isEqualTo("TX-32-1234567");
        assertThat(texas().registered()).as("persisted").isTrue();

        NexusRow cleared = nexus.update(set(after.salesMinor() * 10, null, false));
        assertThat(cleared.registrationNumber()).as("un-registering clears the number").isNull();
        assertThat(cleared.status()).isEqualTo(NexusStatus.BELOW);
    }

    @Test
    void badInputIsRefusedAndOnlyStaffWithThePermissionsMayReadOrChange() {
        assertThatThrownBy(() -> nexus.update(new UpdateNexus(null, "Texas", -1, -2, true, " ",
                LocalDate.now().plusDays(1), null))).isInstanceOfSatisfying(ReturnRuleViolation.class,
                ex -> assertThat(ex.fields()).containsExactlyInAnyOrder("jurisdiction", "salesThresholdMinor",
                        "transactionsThreshold", "registeredOn", "registrationNumber"));

        actors.seller("org_a", "payouts:read");
        assertThatThrownBy(() -> nexus.tracker(YearMonth.now())).isInstanceOf(ForbiddenActionException.class);
        actors.service("tax:read");
        assertThat(nexus.tracker(YearMonth.now())).isNotNull();
        assertThatThrownBy(() -> nexus.update(set(1, null, false))).isInstanceOf(ForbiddenActionException.class);
    }
}
