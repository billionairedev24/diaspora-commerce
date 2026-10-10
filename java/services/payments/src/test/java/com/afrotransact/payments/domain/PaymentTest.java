package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private Payment payment() {
        return Payment.create(UUID.randomUUID(), "ORD-1", "cus_1", Fixtures.usd(3300), UUID.randomUUID(), NOW,
                Map.of(), List.of(Fixtures.line("L1", "org_a", "x", Fulfilment.SELF, 1, 1000, 100, 0),
                        Fixtures.line("L2", "org_b", "x", Fulfilment.SELF, 1, 2000, 200, 0)), NOW);
    }

    @Test
    void amountMustEqualTheLines() {
        assertThatThrownBy(() -> Payment.create(UUID.randomUUID(), "o", null, Fixtures.usd(1), UUID.randomUUID(),
                NOW, Map.of(), List.of(Fixtures.line("L1", "a", "x", Fulfilment.SELF, 1, 2, 0, 0)), NOW))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.amount-mismatch"));
    }

    @Test
    void threeDSecureThenAuthorisedThenCapturedPerShipment() {
        Payment p = payment().syncStatus(PaymentStatus.REQUIRES_ACTION, null, NOW);
        assertThat(p.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThatThrownBy(() -> payment().capture(UUID.randomUUID(), Fixtures.usd(1100), List.of(), false, NOW))
                .isInstanceOf(PaymentsStateConflict.class);
        p = p.syncStatus(PaymentStatus.AUTHORIZED, null, NOW);
        assertThat(p.authorizedAt()).isEqualTo(NOW);
        LineSplit l1 = new LineSplit("L1", "org_a", 1000, Fixtures.usd(100), Fixtures.usd(0), false);
        p = p.capture(UUID.randomUUID(), Fixtures.usd(1100), List.of(l1), false, NOW);
        assertThat(p.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
        assertThat(p.line("L1").orElseThrow().isCaptured()).isTrue();
        // Stripe reports succeeded after the first partial capture: we keep PARTIALLY_CAPTURED.
        assertThat(p.syncStatus(PaymentStatus.CAPTURED, null, NOW).status())
                .isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
        p = p.capture(UUID.randomUUID(), Fixtures.usd(2200), List.of(), false, NOW);
        assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
        Payment captured = p;
        assertThatThrownBy(() -> captured.cancel(NOW)).isInstanceOf(PaymentsStateConflict.class);
    }

    @Test
    void declinedThenRetriedSuccessfully() {
        Payment failed = payment().syncStatus(PaymentStatus.FAILED, "Your card was declined.", NOW);
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.failureMessage()).contains("declined");
        Payment ok = failed.syncStatus(PaymentStatus.AUTHORIZED, null, NOW);
        assertThat(ok.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(ok.failureMessage()).isNull();
        assertThat(PaymentStatus.fromStripe("requires_capture")).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void cannotCaptureMoreThanAuthorised() {
        Payment p = payment().syncStatus(PaymentStatus.AUTHORIZED, null, NOW);
        assertThatThrownBy(() -> p.capture(UUID.randomUUID(), Fixtures.usd(3301), List.of(), false, NOW))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.capture-exceeds-authorised"));
        assertThat(p.cancel(NOW).status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void settlementHoldAndSavedMethods() {
        Settlement s = Settlement.delivered("o", SettlementKind.PRODUCT, NOW, Duration.ofDays(7), NOW);
        assertThat(s.isMatured(NOW.plusSeconds(7 * 86_400 - 1))).isFalse();
        assertThat(s.isMatured(NOW.plusSeconds(7 * 86_400))).isTrue();
        assertThatThrownBy(() -> SavedPaymentMethod.requireRemovable("pm_1", "pm_1"))
                .isInstanceOfSatisfying(PaymentsStateConflict.class,
                        e -> assertThat(e.code()).isEqualTo("payments.default-method-required"));
        SavedPaymentMethod.requireRemovable("pm_2", "pm_1");
        assertThat(PaymentMethodType.ofStripe("us_bank_account")).contains(PaymentMethodType.US_BANK_ACCOUNT);
    }
}
