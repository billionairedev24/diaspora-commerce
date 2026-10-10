package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CancellationPolicyTest {

    @Test
    void moderateRefundsAllThenHalfThenNothing() {
        CancellationPolicy p = CancellationPolicy.moderate();
        assertThat(p.refundShareBps(Duration.ofHours(72))).isEqualTo(10_000);
        assertThat(p.refundShareBps(Duration.ofHours(48))).isEqualTo(10_000);
        assertThat(p.refundShareBps(Duration.ofHours(30))).isEqualTo(5_000);
        assertThat(p.refundShareBps(Duration.ofHours(10))).isZero();
        assertThat(p.refundShareBps(Duration.ofHours(-2))).isZero();
        assertThat(p.text()).isEqualTo("Free up to 48 hours before. 24–48 hours: half the deposit is refunded. "
                + "Under 24 hours: deposit kept.");
    }

    @Test
    void flexibleAndStrictWindows() {
        assertThat(CancellationPolicy.flexible().refundShareBps(Duration.ofHours(25))).isEqualTo(10_000);
        assertThat(CancellationPolicy.flexible().refundShareBps(Duration.ofMinutes(23 * 60 + 59))).isZero();
        assertThat(CancellationPolicy.strict().refundShareBps(Duration.ofDays(7))).isEqualTo(10_000);
        assertThat(CancellationPolicy.strict().refundShareBps(Duration.ofDays(6))).isZero();
        assertThat(CancellationPolicy.strict().text()).startsWith("Free up to 7 days before");
    }

    @Test
    void customPolicyBuildsTiersAndText() {
        CancellationPolicy p = CancellationPolicy.custom(new CustomPolicySettings(72, true, 24, 25, NoShowRule.FULL,
                48, 1));
        assertThat(p.kind()).isEqualTo(PolicyKind.CUSTOM);
        assertThat(p.refundShareBps(Duration.ofHours(80))).isEqualTo(10_000);
        assertThat(p.refundShareBps(Duration.ofHours(30))).isEqualTo(2_500);
        assertThat(p.refundShareBps(Duration.ofHours(5))).isZero();
        assertThat(p.text()).isEqualTo("Free cancellation up to 3 days before. 24 hours–3 days before: 25% of the "
                + "deposit back. Later than that the deposit is kept. No-shows are charged the full price. "
                + "Reschedule free up to 2 days before (max 1).");
    }

    @Test
    void customPolicyIsValidated() {
        assertThatThrownBy(() -> CancellationPolicy.custom(new CustomPolicySettings(24, true, 48, 50,
                NoShowRule.DEPOSIT, 24, 2)))
                .isInstanceOf(BookingsRuleViolation.class)
                .satisfies(e -> assertThat(((BookingsRuleViolation) e).fieldErrors()).containsKey("partialFromHours"));
    }

    @Test
    void reschedulesAllowedInsideWindowAndLimit() {
        CancellationPolicy p = CancellationPolicy.flexible();
        assertThat(p.allowsReschedule(Duration.ofHours(30), 0)).isTrue();
        assertThat(p.allowsReschedule(Duration.ofHours(30), 2)).isFalse();
        assertThat(p.allowsReschedule(Duration.ofHours(3), 0)).isFalse();
    }
}
