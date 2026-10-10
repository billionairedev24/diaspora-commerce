package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SupplierInvoiceTest {

    private final PurchaseOrder po = Fixtures.sent(Fixtures.po(Fixtures.approvedAccra(),
            List.of(PoLine.of(1, UUID.randomUUID(), "AT-JOL-6", "Jollof", "ACC-J", 10, 1_000))));

    private SupplierInvoice invoice(MatchOutcome outcome) {
        return SupplierInvoice.register(UUID.randomUUID(), po, "INV-77", Fixtures.TODAY, "GHS",
                new FxRate("GHS", "USD", new BigDecimal("15.80"), Fixtures.TODAY),
                List.of(new InvoiceLine(1, 10, 1_000)),
                new MatchResult(outcome, List.of(), 0, 0, null, List.of()), "fin", Fixtures.NOW);
    }

    @Test
    void statusFollowsTheMatchOutcome() {
        assertThat(invoice(MatchOutcome.MATCH).status()).isEqualTo(InvoiceStatus.MATCHED);
        assertThat(invoice(MatchOutcome.EXCEPTION).status()).isEqualTo(InvoiceStatus.EXCEPTION);
        assertThat(invoice(MatchOutcome.HOLD).status()).isEqualTo(InvoiceStatus.ON_HOLD);
        assertThat(invoice(MatchOutcome.MATCH).totalMinor()).isEqualTo(10_000);
    }

    @Test
    void financeCanApproveOnlyExceptionsWithANote() {
        SupplierInvoice exception = invoice(MatchOutcome.EXCEPTION);
        assertThatThrownBy(() -> exception.approveException("fin", "", Fixtures.NOW))
                .isInstanceOf(RetailRuleViolation.class);
        SupplierInvoice approved = exception.approveException("fin", "freight surcharge agreed", Fixtures.NOW);
        assertThat(approved.status()).isEqualTo(InvoiceStatus.APPROVED);
        assertThat(approved.status().payable()).isTrue();
        assertThatThrownBy(() -> invoice(MatchOutcome.HOLD).approveException("fin", "x", Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class);
    }

    @Test
    void heldInvoicesAreRematchedAndPaymentIsReleasedOnlyWhenPayable() {
        SupplierInvoice held = invoice(MatchOutcome.HOLD);
        assertThatThrownBy(() -> held.releasePayment(Fixtures.NOW)).isInstanceOf(RetailStateConflict.class);
        SupplierInvoice matched = held.rematch(new MatchResult(MatchOutcome.MATCH, List.of(), 0, 0, null, List.of()),
                Fixtures.NOW);
        assertThat(matched.status()).isEqualTo(InvoiceStatus.MATCHED);
        assertThatThrownBy(() -> matched.rematch(matched.match(), Fixtures.NOW))
                .isInstanceOf(RetailStateConflict.class);
        assertThat(matched.releasePayment(Fixtures.NOW).status()).isEqualTo(InvoiceStatus.PAYMENT_RELEASED);
        assertThat(invoice(MatchOutcome.EXCEPTION).reject("fin", "wrong price", Fixtures.NOW).status())
                .isEqualTo(InvoiceStatus.REJECTED);
    }
}
