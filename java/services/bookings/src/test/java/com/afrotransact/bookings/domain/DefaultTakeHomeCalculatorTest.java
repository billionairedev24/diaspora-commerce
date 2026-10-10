package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultTakeHomeCalculatorTest {

    private final TakeHomeCalculator calculator = new DefaultTakeHomeCalculator(FeeSchedule.defaults());

    @Test
    void takeHomeAfterCommissionAndTwoCardCharges() {
        TakeHome t = calculator.takeHome(18_000, 4_000, "USD");
        assertThat(t.commissionMinor()).isEqualTo(1_800);
        assertThat(t.cardFeesMinor()).isEqualTo(146 + 436);
        assertThat(t.takeHomeMinor()).isEqualTo(18_000 - 1_800 - 582);
        assertThat(t.balanceMinor()).isEqualTo(14_000);
    }

    @Test
    void fullDepositIsOneCharge() {
        TakeHome t = calculator.takeHome(3_000, 3_000, "USD");
        assertThat(t.cardFeesMinor()).isEqualTo(87 + 30);
        assertThat(t.takeHomeMinor()).isEqualTo(3_000 - 300 - 117);
    }

    @Test
    void commissionOnlyOnWhatTheCustomerPaid() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        Booking.Cancellation c = b.cancel(Party.CUSTOMER, null, b.startAt().minusSeconds(30 * 3600), 1500);
        assertThat(c.quote().refundMinor()).isEqualTo(2_000);
        BookingEarnings e = calculator.earnings(b);
        assertThat(e.collectedMinor()).isEqualTo(2_000);
        assertThat(e.commissionMinor()).isEqualTo(200);
        assertThat(e.cardFeesMinor()).isEqualTo(146);
        assertThat(e.netMinor()).isEqualTo(2_000 - 200 - 146);
        assertThat(e.balanceDueMinor()).isZero();
    }
}
