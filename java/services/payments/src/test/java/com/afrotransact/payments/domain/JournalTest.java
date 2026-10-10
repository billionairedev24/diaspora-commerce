package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JournalTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void balancedJournalIsAccepted() {
        Journal j = Journal.builder(JournalType.TRANSFER, "k").order("o1")
                .debit(LedgerAccount.sellerPayable("org_a"), Fixtures.usd(1000), "org_a")
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), Fixtures.usd(1000))
                .build(UUID.randomUUID(), NOW);
        assertThat(j.lines()).hasSize(2);
        assertThat(j.debitTotal("USD")).isEqualTo(1000);
    }

    @Test
    void unbalancedJournalIsRejected() {
        assertThatThrownBy(() -> Journal.builder(JournalType.TRANSFER, "k")
                .debit(LedgerAccount.sellerPayable("org_a"), Fixtures.usd(1000))
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), Fixtures.usd(999))
                .build(UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.journal-unbalanced"));
    }

    @Test
    void mustBalancePerCurrency() {
        assertThatThrownBy(() -> Journal.builder(JournalType.TRANSFER, "k")
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), Money.of(100, "USD"))
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), Money.of(100, "EUR"))
                .build(UUID.randomUUID(), NOW))
                .isInstanceOf(PaymentsRuleViolation.class).hasMessageContaining("EUR");
    }

    @Test
    void builderDropsZeroAndFlipsNegativeAmounts() {
        Journal j = Journal.builder(JournalType.CAPTURE, "k")
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), Fixtures.usd(-50))
                .credit(LedgerAccount.of(Chart.STRIPE_FEES), Fixtures.usd(0))
                .debit(LedgerAccount.of(Chart.STRIPE_FEES), Fixtures.usd(50))
                .build(UUID.randomUUID(), NOW);
        assertThat(j.lines()).extracting(JournalLine::side).containsExactly(EntrySide.CREDIT, EntrySide.DEBIT);
    }

    @Test
    void needsTwoLinesAndPositiveAmounts() {
        assertThatThrownBy(() -> new Journal(UUID.randomUUID(), "k", JournalType.CAPTURE, null, null, null, null,
                NOW, List.of())).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> new JournalLine(LedgerAccount.of(Chart.STRIPE_FEES), EntrySide.DEBIT,
                Fixtures.usd(0), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerAccount(Chart.SELLER_PAYABLE, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerAccount(Chart.STRIPE_CLEARING, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balancesFollowTheNormalSide() {
        assertThat(new AccountBalance(LedgerAccount.sellerPayable("o"), "USD", 100, 900).balance()).isEqualTo(800);
        assertThat(new AccountBalance(LedgerAccount.of(Chart.STRIPE_CLEARING), "USD", 900, 100).balance())
                .isEqualTo(800);
    }
}
