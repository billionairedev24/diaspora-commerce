package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiCreditsTest {

    @Test
    void costRoundsUpToWholeCredits() {
        assertThat(AiCredits.fromCostMicros(0)).isZero();
        assertThat(AiCredits.fromCostMicros(1)).isEqualTo(1);
        assertThat(AiCredits.fromCostMicros(10_000)).isEqualTo(1);
        assertThat(AiCredits.fromCostMicros(10_001)).isEqualTo(2);
        assertThatThrownBy(() -> AiCredits.fromCostMicros(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
