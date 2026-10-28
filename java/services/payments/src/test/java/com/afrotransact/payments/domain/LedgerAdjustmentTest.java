package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LedgerAdjustmentTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private static JournalLine debit(Chart chart, String key, long minor) {
        return new JournalLine(new LedgerAccount(chart, key), EntrySide.DEBIT, Money.of(minor, "USD"), null);
    }

    private static JournalLine credit(Chart chart, String key, long minor) {
        return new JournalLine(new LedgerAccount(chart, key), EntrySide.CREDIT, Money.of(minor, "USD"), null);
    }

    private static LedgerAdjustment proposed() {
        return LedgerAdjustment.propose(UUID.randomUUID(), null, "ch_123456", "Stripe fee booked twice for the charge",
                List.of(debit(Chart.STRIPE_CLEARING, "", 250), credit(Chart.STRIPE_FEES, "", 250)), "alice", NOW);
    }

    @Test
    void proposingChecksReasonBalanceAndStripeRef() {
        assertThatThrownBy(() -> LedgerAdjustment.propose(UUID.randomUUID(), null, "ch_1", "short",
                List.of(debit(Chart.STRIPE_FEES, "", 1), credit(Chart.COMMISSION_REVENUE, "", 1)), "alice", NOW))
                .isInstanceOf(PaymentsRuleViolation.class).hasMessageContaining("at least");
        assertThatThrownBy(() -> LedgerAdjustment.propose(UUID.randomUUID(), null, null, "Reclass a fee to commission",
                List.of(debit(Chart.STRIPE_FEES, "", 100), credit(Chart.COMMISSION_REVENUE, "", 90)), "alice", NOW))
                .isInstanceOf(PaymentsRuleViolation.class).hasMessageContaining("equal");
        assertThatThrownBy(() -> LedgerAdjustment.propose(UUID.randomUUID(), null, null, "Clearing is short by a fee",
                List.of(debit(Chart.STRIPE_CLEARING, "", 100), credit(Chart.STRIPE_FEES, "", 100)), "alice", NOW))
                .isInstanceOf(PaymentsRuleViolation.class).hasMessageContaining("Stripe object");
    }

    @Test
    void theProposerCannotApproveButAnotherPersonCan() {
        LedgerAdjustment a = proposed();
        assertThatThrownBy(() -> a.approve("alice", null, UUID.randomUUID(), NOW)).isInstanceOf(PaymentsForbidden.class);

        LedgerAdjustment posted = a.approve("bob", "checked against Stripe", UUID.randomUUID(), NOW);
        assertThat(posted.status()).isEqualTo(AdjustmentStatus.POSTED);
        assertThat(posted.decidedBy()).isEqualTo("bob");
        assertThatThrownBy(() -> posted.approve("carol", null, UUID.randomUUID(), NOW))
                .isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void rejectingNeedsAReasonAndTheProposerMayWithdraw() {
        LedgerAdjustment a = proposed();
        assertThatThrownBy(() -> a.reject("alice", " ", NOW)).isInstanceOf(PaymentsRuleViolation.class);
        assertThat(a.reject("alice", "withdrawn: wrong account", NOW).status()).isEqualTo(AdjustmentStatus.REJECTED);
    }

    @Test
    void theJournalIsBalancedBookedAgainstTheStripeObjectAndHasAStableId() {
        LedgerAdjustment a = proposed();
        Journal j = a.toJournal(NOW);
        assertThat(j.type()).isEqualTo(JournalType.ADJUSTMENT);
        assertThat(j.stripeRef()).isEqualTo("ch_123456");
        assertThat(j.key()).isEqualTo("adjustment:" + a.id());
        assertThat(Journal.imbalance(j.lines())).isEmpty();
        assertThat(a.toJournal(NOW.plusSeconds(5)).id()).isEqualTo(j.id());
    }
}
