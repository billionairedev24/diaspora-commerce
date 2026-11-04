package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S298: withholding, partial withholding, release, settlement against transfers, and the earnings pool. */
class PlanFeeWithholdingTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final UUID JOURNAL = UUID.randomUUID();

    private static Money usd(long minor) {
        return Money.of(minor, "USD");
    }

    private static PlanFeeWithholding decide(long requested, long available) {
        return PlanFeeWithholding.decide(UUID.randomUUID(), "org_s", "in_1", "INV-0042", usd(requested),
                usd(available), JOURNAL, NOW);
    }

    private static List<String> lines(Journal j) {
        return j.lines().stream().map(l -> l.side() + " " + l.account().chart().code() + " " + l.amount().minor() + " "
                + l.orderId()).toList();
    }

    @Test
    void withholdsTheWholeInvoiceWhenEarningsCoverIt() {
        PlanFeeWithholding w = decide(3900, 10_000);
        assertThat(w.status()).isEqualTo(PlanFeeWithholdingStatus.WITHHELD);
        assertThat(w.withheld()).isEqualTo(usd(3900));
        assertThat(w.outstanding()).isEqualTo(usd(3900));
        assertThat(w.reason()).isNull();
        assertThat(w.journalId()).isEqualTo(JOURNAL);
    }

    @Test
    void withholdsWhatIsAvailableAndTheCardPaysTheRest() {
        PlanFeeWithholding w = decide(3900, 1500);
        assertThat(w.status()).isEqualTo(PlanFeeWithholdingStatus.WITHHELD);
        assertThat(w.withheld()).isEqualTo(usd(1500));
        assertThat(w.requested()).isEqualTo(usd(3900));
        assertThat(w.reason()).contains("card pays the rest");
    }

    @Test
    void declinesWhenNothingIsAvailable() {
        PlanFeeWithholding w = decide(3900, 0);
        assertThat(w.status()).isEqualTo(PlanFeeWithholdingStatus.DECLINED);
        assertThat(w.withheld().isZero()).isTrue();
        assertThat(w.journalId()).isNull();
        assertThat(w.outstanding().isZero()).isTrue();
        assertThat(decide(3900, -500).status()).as("a negative pool is nothing")
                .isEqualTo(PlanFeeWithholdingStatus.DECLINED);
        assertThatThrownBy(() -> w.release(usd(1), NOW)).isInstanceOf(PaymentsStateConflict.class);
        assertThatThrownBy(() -> decide(0, 100)).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void releasesInPartsAndInFullButNeverMoreThanWithheld() {
        PlanFeeWithholding w = decide(3900, 10_000);
        PlanFeeWithholding part = w.release(usd(900), NOW);
        assertThat(part.status()).isEqualTo(PlanFeeWithholdingStatus.WITHHELD);
        assertThat(part.releasable()).isEqualTo(usd(3000));
        assertThat(part.outstanding()).isEqualTo(usd(3000));
        assertThatThrownBy(() -> part.release(usd(3001), NOW)).isInstanceOf(PaymentsRuleViolation.class);
        PlanFeeWithholding all = part.release(usd(3000), NOW);
        assertThat(all.status()).isEqualTo(PlanFeeWithholdingStatus.RELEASED);
        assertThat(all.outstanding().isZero()).isTrue();
        assertThatThrownBy(() -> all.release(usd(1), NOW)).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void transfersRecoverItThenAReleasePaysItBack() {
        PlanFeeWithholding w = decide(3900, 10_000).settledBy(usd(2000), NOW).settledBy(usd(1900), NOW);
        assertThat(w.outstanding().isZero()).isTrue();
        assertThatThrownBy(() -> w.settledBy(usd(1), NOW)).as("nothing left to recover")
                .isInstanceOf(PaymentsRuleViolation.class);
        PlanFeeWithholding released = w.release(usd(3900), NOW);
        assertThat(released.outstanding()).as("owed back to the seller").isEqualTo(usd(-3900));
        assertThatThrownBy(() -> released.settledBy(usd(100), NOW)).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> released.settledBy(usd(-4000), NOW)).isInstanceOf(PaymentsRuleViolation.class);
        assertThat(released.settledBy(usd(-3900), NOW).outstanding().isZero()).isTrue();
    }

    @Test
    void postingsDebitThePayableWithoutAnOrderAndRecoverItFromTheTransferredOrder() {
        PlanFeeWithholding w = decide(3900, 10_000);
        Journal withheld = Postings.planFeeWithheld(JOURNAL, NOW, "plan-fee:in_1", w);
        assertThat(withheld.type()).isEqualTo(JournalType.PLAN_FEE_WITHHELD);
        assertThat(withheld.description()).isEqualTo("Plan fee (invoice INV-0042)");
        assertThat(lines(withheld)).containsExactly("DEBIT 2000 3900 null", "CREDIT 4600 3900 null");
        assertThat(withheld.debitTotal("USD")).isEqualTo(3900);

        Journal settled = Postings.planFeeSettled(UUID.randomUUID(), NOW, "k", "ORD-1", w, usd(3900));
        assertThat(lines(settled)).containsExactly("DEBIT 2000 3900 ORD-1", "CREDIT 2000 3900 null");
        assertThat(settled.description()).isEqualTo("Plan fee (invoice INV-0042) deducted from order ORD-1");
        Journal paidBack = Postings.planFeeSettled(UUID.randomUUID(), NOW, "k", "ORD-2", w, usd(-3900));
        assertThat(lines(paidBack)).containsExactly("DEBIT 2000 3900 null", "CREDIT 2000 3900 ORD-2");

        Journal released = Postings.planFeeReleased(UUID.randomUUID(), NOW, "k", w, usd(500), "invoice voided");
        assertThat(lines(released)).containsExactly("DEBIT 4600 500 null", "CREDIT 2000 500 null");
        assertThat(released.description()).isEqualTo("Plan fee (invoice INV-0042) released (invoice voided)");
    }

    @Test
    void thePoolIsDeliveredUndisputedEarningsLessWhatIsAlreadyClaimed() {
        Map<String, Long> payable = Map.of("A", 5000L, "B", 3000L, "C", 4000L, "D", -700L);
        Set<String> delivered = Set.of("A", "B", "D");
        assertThat(EarningsPool.withholdable(payable, delivered, Set.of(), 0, 0, "USD")).as("C is not delivered")
                .isEqualTo(usd(8000));
        assertThat(EarningsPool.withholdable(payable, delivered, Set.of("A"), 0, 0, "USD"))
                .as("A is under an open dispute").isEqualTo(usd(3000));
        assertThat(EarningsPool.withholdable(payable, delivered, Set.of(), 1000, 2500, "USD"))
                .as("outstanding labels and earlier plan fees").isEqualTo(usd(4500));
        assertThat(EarningsPool.withholdable(payable, delivered, Set.of(), -900, -100, "USD"))
                .as("credits owed back don't add to the pool").isEqualTo(usd(8000));
        assertThat(EarningsPool.withholdable(payable, delivered, Set.of(), 9000, 0, "USD").isZero()).isTrue();
    }
}
