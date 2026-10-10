package com.afrotransact.bookings.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.BalanceChargePolicy;
import com.afrotransact.bookings.domain.FeeSchedule;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import org.junit.jupiter.api.Test;

/** AfroTransact Retail is never charged seller fees by bookings, not even through the configured fallback. */
class RetailBookingFeesTest {

    private static final String RETAIL = "org_afrotransact_retail";

    private final BookingsSettings settings = new BookingsSettings(Duration.ofMinutes(15), Duration.ofHours(4),
            Duration.ofHours(48), 1500, "", new BalanceChargePolicy(Duration.ofDays(5), Duration.ofDays(2), 3,
            Duration.ofHours(6)), new FeeSchedule(1000, 290, 30), Duration.ofMinutes(5), RETAIL);

    @Test
    void fallbackFeesChargeRetailNoCommission() {
        ProviderFeesImpl fees = new ProviderFeesImpl(new UnavailablePayments(), settings,
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
        FeeSchedule retail = fees.forOrg(RETAIL);
        assertThat(retail.commissionBps()).isZero();
        assertThat(retail.source()).isEqualTo(FeeSchedule.RETAIL);
        assertThat(retail.cardPctBps()).isEqualTo(290);
        assertThat(retail.cardFixedMinor()).isEqualTo(30);
        FeeSchedule provider = fees.forOrg("org_provider");
        assertThat(provider.commissionBps()).isEqualTo(1000);
        assertThat(provider.source()).isEqualTo(FeeSchedule.CONFIG);
    }

    @Test
    void retailHasNoLateCancellationFee() {
        assertThat(settings.lateProviderFeeFor(RETAIL)).isZero();
        assertThat(settings.lateProviderFeeFor("org_provider")).isEqualTo(1500);
    }

    /** payments-service is down: every call fails. */
    private static final class UnavailablePayments implements PaymentsClient {

        private static PaymentsClientException down() {
            return new PaymentsClientException(503, "payments.unavailable", "down", null);
        }

        @Override
        public Intent createIntent(CreateIntent request, String idempotencyKey) {
            throw down();
        }

        @Override
        public Intent get(UUID paymentId, boolean refresh) {
            throw down();
        }

        @Override
        public void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor,
                            boolean finalCapture, String idempotencyKey) {
            throw down();
        }

        @Override
        public void cancel(UUID paymentId, String reason, String idempotencyKey) {
            throw down();
        }

        @Override
        public Intent release(UUID paymentId, String reason, String idempotencyKey) {
            throw down();
        }

        @Override
        public Intent chargeOffSession(UUID paymentId, OffSessionCharge request, String idempotencyKey) {
            throw down();
        }

        @Override
        public void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey) {
            throw down();
        }

        @Override
        public void serviceCompleted(String orderId, Instant completedAt) {
            throw down();
        }

        @Override
        public EffectiveFees effectiveFees(String category, String orgId) {
            throw down();
        }
    }
}
