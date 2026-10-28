package com.afrotransact.bookings.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import com.afrotransact.bookings.domain.BalanceChargePolicy;
import com.afrotransact.bookings.domain.FeeSchedule;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import org.junit.jupiter.api.Test;

/**
 * Bookings against a payments with connection fees (it answers kind SERVICE) and against one without them (no kind:
 * it ignores kind=SERVICE and prices the category as a product) or that cannot answer: never fails, and keeps the
 * previous "services" pricing unless payments prices connection fees.
 */
class ConnectionFeeCompatibilityTest {

    private final BookingsSettings settings = new BookingsSettings(Duration.ofMinutes(15), Duration.ofHours(4),
            Duration.ofHours(48), 1500, "", new BalanceChargePolicy(Duration.ofDays(5), Duration.ofDays(2), 3,
            Duration.ofHours(6)), new FeeSchedule(1000, 290, 30), Duration.ofMinutes(5), "org_afrotransact_retail");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void paymentsWithConnectionFeesPricesTheServiceCategory() {
        List<String> asked = new ArrayList<>();
        ProviderFeesImpl fees = new ProviderFeesImpl(new FakePayments((cat, org) -> {
            asked.add(cat);
            return new PaymentsClient.EffectiveFees(800, "PLAN", 290, 30, 2, "USD", "SERVICE");
        }), settings, clock);
        FeeSchedule f = fees.forOrg("org_p", "Hair & Beauty");
        assertThat(f.commissionBps()).isEqualTo(800);
        assertThat(f.source()).isEqualTo("PLAN");
        assertThat(fees.paymentCategory("org_p", "Hair & Beauty")).isEqualTo("hair & beauty");
        assertThat(asked).containsExactly("hair & beauty");
    }

    @Test
    void paymentsWithoutConnectionFeesKeepsTheServicesCommission() {
        List<String> asked = new ArrayList<>();
        ProviderFeesImpl fees = new ProviderFeesImpl(new FakePayments((cat, org) -> {
            asked.add(cat);
            // An older payments: no kind; an unlisted category gets the default commission.
            return "services".equals(cat) ? new PaymentsClient.EffectiveFees(900, "CATEGORY", 290, 30, 1, "USD")
                    : new PaymentsClient.EffectiveFees(1200, "DEFAULT", 290, 30, 1, "USD");
        }), settings, clock);
        FeeSchedule f = fees.forOrg("org_p", "massage");
        assertThat(f.commissionBps()).isEqualTo(900);
        assertThat(fees.paymentCategory("org_p", "massage")).isEqualTo("services");
        assertThat(asked).containsExactly("massage", "services");
    }

    @Test
    void paymentsThatCannotAnswerFallsBackToConfiguredFeesAndServices() {
        ProviderFeesImpl fees = new ProviderFeesImpl(new FakePayments((cat, org) -> {
            throw new PaymentsClientException(404, null, "Payments: Not Found", null);
        }), settings, clock);
        FeeSchedule f = fees.forOrg("org_p", "massage");
        assertThat(f.commissionBps()).isEqualTo(1000);
        assertThat(f.source()).isEqualTo(FeeSchedule.CONFIG);
        assertThat(fees.paymentCategory("org_p", "massage")).isEqualTo("services");
    }

    /** Only effective fees are used here. */
    private record FakePayments(BiFunction<String, String, EffectiveFees> effective) implements PaymentsClient {

        @Override
        public EffectiveFees effectiveFees(String category, String orgId) {
            return effective.apply(category, orgId);
        }

        @Override
        public Intent createIntent(CreateIntent request, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Intent get(UUID paymentId, boolean refresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor,
                            boolean finalCapture, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(UUID paymentId, String reason, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Intent release(UUID paymentId, String reason, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Intent chargeOffSession(UUID paymentId, OffSessionCharge request, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void serviceCompleted(String orderId, Instant completedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
