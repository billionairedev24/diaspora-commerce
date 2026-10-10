package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultTakeHomeCalculatorTest {

    private final TakeHomeCalculator calculator = new DefaultTakeHomeCalculator();
    private final FeeSchedule fees = FeeSchedule.defaults();

    @Test
    void takeHomeAfterCommissionAndTwoCardCharges() {
        TakeHome t = calculator.takeHome(fees, 18_000, 4_000, "USD");
        assertThat(t.commissionMinor()).isEqualTo(1_800);
        assertThat(t.cardFeesMinor()).isEqualTo(146 + 436);
        assertThat(t.takeHomeMinor()).isEqualTo(18_000 - 1_800 - 582);
        assertThat(t.balanceMinor()).isEqualTo(14_000);
        assertThat(t.feeSource()).isEqualTo(FeeSchedule.CONFIG);
    }

    @Test
    void usesTheProvidersFeeScheduleFromPayments() {
        FeeSchedule override = new FeeSchedule(800, 290, 30, "OVERRIDE", 4);
        TakeHome t = calculator.takeHome(override, 18_000, 4_000, "USD");
        assertThat(t.commissionBps()).isEqualTo(800);
        assertThat(t.commissionMinor()).isEqualTo(1_440);
        assertThat(t.takeHomeMinor()).isEqualTo(18_000 - 1_440 - 582);
        assertThat(t.feeSource()).isEqualTo("OVERRIDE");
        assertThat(t.feeVersion()).isEqualTo(4);
    }

    @Test
    void fullDepositIsOneCharge() {
        TakeHome t = calculator.takeHome(fees, 3_000, 3_000, "USD");
        assertThat(t.cardFeesMinor()).isEqualTo(87 + 30);
        assertThat(t.takeHomeMinor()).isEqualTo(3_000 - 300 - 117);
    }

    @Test
    void commissionOnlyOnWhatTheCustomerPaid() {
        Booking b = Fixtures.confirmed(CancellationPolicy.moderate());
        Booking.Cancellation c = b.cancel(Party.CUSTOMER, null, b.startAt().minusSeconds(30 * 3600), 1500);
        assertThat(c.quote().refundMinor()).isEqualTo(2_000);
        BookingEarnings e = calculator.earnings(fees, b);
        assertThat(e.collectedMinor()).isEqualTo(2_000);
        assertThat(e.commissionMinor()).isEqualTo(200);
        assertThat(e.cardFeesMinor()).isEqualTo(146);
        assertThat(e.netMinor()).isEqualTo(2_000 - 200 - 146);
        assertThat(e.balanceDueMinor()).isZero();
    }
}
