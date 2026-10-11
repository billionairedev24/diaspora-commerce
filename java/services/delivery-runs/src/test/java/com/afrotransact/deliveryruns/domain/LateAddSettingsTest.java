package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LateAddSettingsTest {

    @Test
    void paymentLinksLastOneToTwentyFourHoursLikeOrdersService() {
        assertThat(new LateAddSettings(true, true, 1).linkExpiryHours()).isEqualTo(1);
        assertThat(new LateAddSettings(true, true, 24).linkExpiryHours()).isEqualTo(24);
        assertThat(LateAddSettings.DEFAULT.linkExpiryHours()).isEqualTo(LateAddSettings.MAX_LINK_EXPIRY_HOURS);
        assertThatThrownBy(() -> new LateAddSettings(true, true, 25)).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("1 to 24 hours");
        assertThatThrownBy(() -> new LateAddSettings(true, true, 0)).isInstanceOf(RuleViolation.class);
    }
}
