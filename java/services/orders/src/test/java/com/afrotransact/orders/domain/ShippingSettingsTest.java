package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ShippingSettingsTest {

    @Test
    void oneFlatFeeAppliesUntilTheOptionalFreeAboveThreshold() {
        ShippingSettings none = ShippingSettingsFixtures.of(599, null, false);
        assertThat(none.deliveryFee(0)).isEqualTo(599);
        assertThat(none.deliveryFee(1_000_000)).as("no threshold: never free").isEqualTo(599);

        ShippingSettings free = ShippingSettingsFixtures.of(599, 5000, false);
        assertThat(free.deliveryFee(4999)).isEqualTo(599);
        assertThat(free.deliveryFee(5000)).as("the threshold itself is free").isZero();
    }

    @Test
    void numbersOutsideTheRangeAreRefusedInPlainWords() {
        assertThatThrownBy(() -> ShippingSettingsFixtures.of(-1, null, false))
                .isInstanceOf(OrdersRuleViolation.class).hasMessageContaining("between 0 and 100.00");
        assertThatThrownBy(() -> ShippingSettingsFixtures.of(10_001, null, false))
                .isInstanceOf(OrdersRuleViolation.class);
        assertThatThrownBy(() -> ShippingSettingsFixtures.of(599, 0L, false))
                .isInstanceOf(OrdersRuleViolation.class).hasMessageContaining("left empty for never");
        assertThat(ShippingSettingsFixtures.of(0, null, false).deliveryFee(100)).as("0 is free, allowed").isZero();
    }

    @Test
    void aChangeIsTheNextVersionAndASameValueSaveIsDetected() {
        ShippingSettings v1 = ShippingSettingsFixtures.of(599, null, false);
        ShippingSettings v2 = v1.next(699, 6000L, false, "staff-2", "Fuel went up this month", Instant.EPOCH);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.updatedBy()).isEqualTo("staff-2");
        assertThat(v1.sameAs(599, null, false)).isTrue();
        assertThat(v1.sameAs(599, null, true)).isFalse();
        assertThat(v1.sameAs(600, null, false)).isFalse();
        assertThat(v2.sameAs(699, 6000L, false)).isTrue();
    }
}
