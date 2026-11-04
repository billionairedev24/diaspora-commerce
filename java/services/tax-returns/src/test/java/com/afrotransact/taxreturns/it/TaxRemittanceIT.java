package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.PaymentRefunded;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import com.afrotransact.taxreturns.application.TaxRemittanceService;
import com.afrotransact.taxreturns.application.TaxRemittanceService.RecordRemittance;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.FilingObligation;
import com.afrotransact.taxreturns.domain.tax.FilingStatus;
import com.afrotransact.taxreturns.domain.tax.TaxRemittance;
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

/**
 * The tax remittance record against PostgreSQL: what the ledger says is due, what staff recorded as filed and paid,
 * what moved after filing, and the voided-then-re-recorded correction path. Each test uses its own past month so the
 * unique "one active remittance per state and month" index never crosses tests.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(StubUpstreams.class)
class TaxRemittanceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final LocalDate FILED = LocalDate.now().minusDays(3);
    private static final long ORDER_TAX = 1_072 + 264;

    private final TaxRemittanceService remittances;
    private final TaxLedgerService ledger;
    private final OrderIntakeService intake;
    private final TestActors actors;

    @Autowired
    TaxRemittanceIT(TaxRemittanceService remittances, TaxLedgerService ledger, OrderIntakeService intake,
                    TestActors actors) {
        this.remittances = remittances;
        this.ledger = ledger;
        this.intake = intake;
        this.actors = actors;
    }

    @BeforeEach
    void asStaff() {
        actors.staff();
    }

    private Orders sellIn(YearMonth month) {
        Orders order = new Orders("cust_" + UUID.randomUUID());
        assertThat(intake.onOrderPlaced(UUID.randomUUID(), order.placed(month.atDay(10).atStartOfDay()
                .toInstant(ZoneOffset.UTC)))).isTrue();
        return order;
    }

    private FilingObligation texas(YearMonth month) {
        return remittances.obligation(month, "US-TX", "USD");
    }

    private RecordRemittance filing(YearMonth month, long paid) {
        return new RecordRemittance(month, "US-TX", "USD", paid, FILED, FILED, "TX-CONF-123", "ACH-998", null);
    }

    @Test
    void whatIsDueIsFiledAndThenTracksWhatMovesAfterFiling() {
        YearMonth month = YearMonth.of(2025, 2);
        Orders order = sellIn(month);
        long due = texas(month).dueMinor();
        assertThat(due).isEqualTo(ORDER_TAX);
        assertThat(texas(month).status()).isEqualTo(FilingStatus.NOT_FILED);
        assertThat(texas(month).outstandingMinor()).isEqualTo(due);

        TaxRemittance filed = remittances.record(filing(month, due));
        assertThat(filed.dueMinor()).as("the amount due is stored with the record").isEqualTo(due);
        assertThat(filed.recordedBy()).isEqualTo("staff_1");
        FilingObligation settled = texas(month);
        assertThat(settled.status()).isEqualTo(FilingStatus.SETTLED);
        assertThat(settled.outstandingMinor()).isZero();
        assertThat(settled.remittance().id()).isEqualTo(filed.id());

        // a refund that belongs to the filed month but reached the ledger after filing (a late event) lowers the
        // liability: we have overpaid the state. Refunds that happen after the month ends count in their own month.
        ledger.onPaymentRefunded(UUID.randomUUID(), new PaymentRefunded(order.orderId.toString(), "pref-" + order.orderId,
                "USD", "SUCCEEDED", month.atDay(25).atStartOfDay().toInstant(ZoneOffset.UTC),
                List.of(new PaymentRefunded.Line(order.dress, 6_499, 536))));
        FilingObligation overpaid = texas(month);
        assertThat(overpaid.dueMinor()).isEqualTo(due - 536);
        assertThat(overpaid.status()).isEqualTo(FilingStatus.OVERPAID);
        assertThat(overpaid.outstandingMinor()).isEqualTo(-536);
        assertThat(overpaid.changedSinceFilingMinor()).isEqualTo(-536);

        // a late sale in the same month pushes it the other way
        sellIn(month);
        assertThat(texas(month).status()).isEqualTo(FilingStatus.OWES_MORE);
        assertThat(texas(month).outstandingMinor()).isEqualTo(ORDER_TAX - 536);
    }

    @Test
    void theOverviewListsEveryMonthNewestFirstWithItsStatus() {
        YearMonth earlier = YearMonth.of(2025, 3);
        YearMonth later = YearMonth.of(2025, 4);
        sellIn(earlier);
        sellIn(later);
        remittances.record(filing(later, texas(later).dueMinor()));

        List<FilingObligation> rows = remittances.obligations(later, 2).stream()
                .filter(o -> o.jurisdiction().equals("US-TX")).toList();
        assertThat(rows).extracting(FilingObligation::month).containsExactly(later, earlier);
        assertThat(rows).extracting(FilingObligation::status).containsExactly(FilingStatus.SETTLED,
                FilingStatus.NOT_FILED);
    }

    @Test
    void aMistakeIsVoidedWithAReasonAndRecordedAgain() {
        YearMonth month = YearMonth.of(2025, 5);
        sellIn(month);
        long due = texas(month).dueMinor();
        TaxRemittance wrong = remittances.record(filing(month, due - 100));
        assertThat(texas(month).status()).isEqualTo(FilingStatus.OWES_MORE);

        assertThatThrownBy(() -> remittances.record(filing(month, due))).isInstanceOf(ConflictException.class)
                .hasMessageContaining("already recorded");
        assertThatThrownBy(() -> remittances.voidRemittance(wrong.id(), "typo"))
                .isInstanceOf(ReturnRuleViolation.class);

        TaxRemittance voided = remittances.voidRemittance(wrong.id(), "Paid amount was keyed in wrong");
        assertThat(voided.active()).isFalse();
        assertThat(voided.voidedBy()).isEqualTo("staff_1");
        assertThat(texas(month).status()).as("a voided record no longer counts").isEqualTo(FilingStatus.NOT_FILED);
        assertThatThrownBy(() -> remittances.voidRemittance(wrong.id(), "Voiding it a second time"))
                .isInstanceOf(ConflictException.class);

        TaxRemittance right = remittances.record(filing(month, due));
        assertThat(texas(month).status()).isEqualTo(FilingStatus.SETTLED);
        assertThat(remittances.history(month)).extracting(TaxRemittance::id)
                .containsExactlyInAnyOrder(wrong.id(), right.id());
        assertThat(remittances.history(month)).filteredOn(TaxRemittance::active).singleElement()
                .extracting(TaxRemittance::id).isEqualTo(right.id());
    }

    @Test
    void aNilReturnForAMonthWithoutSalesIsASettledRecord() {
        YearMonth quiet = YearMonth.of(2024, 12);
        assertThat(texas(quiet).status()).isEqualTo(FilingStatus.NOTHING_DUE);
        remittances.record(filing(quiet, 0));
        assertThat(texas(quiet).status()).isEqualTo(FilingStatus.SETTLED);
        assertThat(remittances.obligations(quiet, 1)).anySatisfy(o -> assertThat(o.jurisdiction()).isEqualTo("US-TX"));
    }

    @Test
    void badInputIsRefusedWithFieldErrors() {
        YearMonth nextMonth = YearMonth.now().plusMonths(1);
        assertThatThrownBy(() -> remittances.record(filing(nextMonth, 100))).isInstanceOf(ReturnRuleViolation.class)
                .hasMessageContaining("hasn't started");
        assertThatThrownBy(() -> remittances.record(new RecordRemittance(YearMonth.of(2025, 1), "Texas", "USD", -5,
                LocalDate.now().plusDays(1), null, null, null, null)))
                .isInstanceOfSatisfying(ReturnRuleViolation.class, ex -> assertThat(ex.fields())
                        .containsExactlyInAnyOrder("jurisdiction", "paidMinor", "filedOn", "paidOn"));
        assertThatThrownBy(() -> remittances.obligations(YearMonth.now(), 0)).isInstanceOf(ReturnRuleViolation.class);
    }

    @Test
    void onlyStaffWithTheRightPermissionsMayReadOrRecord() {
        actors.seller("org_a", "payouts:read");
        assertThatThrownBy(() -> remittances.obligations(YearMonth.now(), 1))
                .isInstanceOf(ForbiddenActionException.class);
        actors.service("tax:read");
        assertThat(remittances.obligations(YearMonth.of(2025, 1), 1)).isNotNull();
        assertThatThrownBy(() -> remittances.record(filing(YearMonth.of(2025, 1), 0)))
                .isInstanceOf(ForbiddenActionException.class);
    }
}
