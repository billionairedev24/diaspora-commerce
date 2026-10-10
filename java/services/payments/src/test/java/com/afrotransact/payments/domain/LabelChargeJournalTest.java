package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S256: journals of shipping labels charged to sellers. */
class LabelChargeJournalTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private static LabelCharge charge(long cost, long handling) {
        return LabelCharge.charge(UUID.randomUUID(), "lbl-1", "org_s", "shp-1", "ord-1", "ups", "03", "1Z999",
                Money.of(cost, "USD"), Money.of(handling, "USD"), UUID.randomUUID(), UUID.randomUUID(), NOW, NOW);
    }

    private static List<String> lines(Journal j) {
        return j.lines().stream().map(l -> l.side() + " " + l.account().display() + " " + l.amount().minor() + " "
                + l.orgId() + " " + l.orderId()).toList();
    }

    @Test
    void chargeDebitsTheSellerPayableAndCreditsCarrierLabelsPayableAndHandling() {
        LabelCharge c = charge(999, 125);
        Journal j = Postings.labelCharge(UUID.randomUUID(), NOW, "label-charge:lbl-1", c);

        assertThat(j.type()).isEqualTo(JournalType.LABEL_CHARGE);
        assertThat(j.orderId()).as("a label charge is the organisation's, not an order's").isNull();
        assertThat(lines(j)).containsExactly(
                "DEBIT 2000:org_s 1124 org_s null",
                "CREDIT 2400 999 org_s null",
                "CREDIT 4500 125 org_s null");
        assertThat(Journal.imbalance(j.lines())).isEmpty();
        assertThat(j.description()).contains("1Z999", "shp-1");
    }

    @Test
    void withoutHandlingTheChargeHasTwoLines() {
        Journal j = Postings.labelCharge(UUID.randomUUID(), NOW, "k", charge(850, 0));
        assertThat(lines(j)).containsExactly("DEBIT 2000:org_s 850 org_s null", "CREDIT 2400 850 org_s null");
    }

    @Test
    void creditIsTheExactReverseOfTheCharge() {
        LabelCharge c = charge(999, 125);
        Journal charge = Postings.labelCharge(UUID.randomUUID(), NOW, "c", c);
        Journal credit = Postings.labelCredit(UUID.randomUUID(), NOW, "v", c);

        assertThat(credit.type()).isEqualTo(JournalType.LABEL_CREDIT);
        assertThat(lines(credit)).containsExactly(
                "DEBIT 2400 999 org_s null",
                "DEBIT 4500 125 org_s null",
                "CREDIT 2000:org_s 1124 org_s null");
        long net = 0;
        for (Journal j : List.of(charge, credit)) {
            for (JournalLine l : j.lines()) {
                if (l.account().equals(LedgerAccount.sellerPayable("org_s"))) {
                    net += l.signedMinor();
                }
            }
        }
        assertThat(net).as("purchase + void leaves the payable unchanged").isZero();
    }

    @Test
    void recoveryMovesTheChargeOntoTheOrderPayableAndCreditsGoBackWithTheTransfer() {
        Journal recovered = Postings.labelRecovery(UUID.randomUUID(), NOW, "r", "ord-9", "org_s",
                Money.of(1124, "USD"));
        assertThat(recovered.type()).isEqualTo(JournalType.LABEL_RECOVERY);
        assertThat(lines(recovered)).containsExactly(
                "DEBIT 2000:org_s 1124 org_s ord-9",
                "CREDIT 2000:org_s 1124 org_s null");

        Journal paidBack = Postings.labelRecovery(UUID.randomUUID(), NOW, "p", "ord-9", "org_s",
                Money.of(-300, "USD"));
        assertThat(lines(paidBack)).containsExactly(
                "DEBIT 2000:org_s 300 org_s null",
                "CREDIT 2000:org_s 300 org_s ord-9");
        assertThat(Journal.imbalance(paidBack.lines())).isEmpty();
    }

    @Test
    void aChargeIsPositiveAndCreditedOnlyOnce() {
        assertThatThrownBy(() -> charge(0, 0)).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> charge(-1, 5)).isInstanceOf(PaymentsRuleViolation.class);
        LabelCharge c = charge(500, 0);
        assertThat(c.amount()).isEqualTo(Money.of(500, "USD"));
        LabelCharge credited = c.credited(UUID.randomUUID(), NOW);
        assertThat(credited.status()).isEqualTo(LabelChargeStatus.CREDITED);
        assertThat(credited.creditedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> credited.credited(UUID.randomUUID(), NOW))
                .isInstanceOf(PaymentsStateConflict.class);
    }
}
