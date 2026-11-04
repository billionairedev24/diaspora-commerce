package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReconciliationItemTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private static ReconciliationItem open(long ledger, long stripe) {
        return ReconciliationItem.open(UUID.randomUUID(), UUID.randomUUID(), NOW,
                new ReconciliationDrift("ch_abc123", ledger, stripe), "USD", NOW);
    }

    @Test
    void theDifferenceIsLedgerMinusStripe() {
        assertThat(open(1000, 940).differenceMinor()).isEqualTo(60);
    }

    @Test
    void explainingNeedsANoteAndAnOpenItem() {
        ReconciliationItem item = open(1000, 940);
        assertThatThrownBy(() -> item.explain("alice", "n/a", NOW)).isInstanceOf(PaymentsRuleViolation.class);

        ReconciliationItem explained = item.explain("alice", "Stripe fee settles next day (timing)", NOW);
        assertThat(explained.status()).isEqualTo(ReconciliationStatus.EXPLAINED);
        assertThat(explained.resolved()).isTrue();
        assertThatThrownBy(() -> explained.explain("bob", "second explanation here", NOW))
                .isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void anExplanationCarriesOverOnlyForTheSameDifference() {
        ReconciliationItem explained = open(1000, 940).explain("alice", "Timing of the Stripe fee", NOW);
        assertThat(open(1000, 940).carryOver(explained).status()).isEqualTo(ReconciliationStatus.EXPLAINED);
        assertThat(open(1000, 900).carryOver(explained).status()).isEqualTo(ReconciliationStatus.OPEN);
    }

    @Test
    void aSupersededItemCannotBeAdjusted() {
        assertThatThrownBy(() -> open(1, 2).superseded().adjusted("bob", NOW)).isInstanceOf(PaymentsRuleViolation.class);
    }
}
