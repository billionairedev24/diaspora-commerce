package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class RatingSummaryTest {

    @Test
    void noReviewsMeansZeros() {
        RatingSummary s = RatingSummary.ofCounts(0, 0, 0, 0, 0);
        assertThat(s).isEqualTo(RatingSummary.NONE);
        assertThat(s.average()).isEqualByComparingTo("0");
        assertThat(s.count()).isZero();
        assertThat(s.breakdown()).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    void theAverageIsRoundedToTwoDecimalsAndTheBreakdownIsKept() {
        RatingSummary s = RatingSummary.ofCounts(1, 0, 2, 3, 4);
        // (1 + 6 + 12 + 20) / 10
        assertThat(s.average()).isEqualByComparingTo(new BigDecimal("3.90"));
        assertThat(s.count()).isEqualTo(10);
        assertThat(s.breakdown()).containsExactly(1, 0, 2, 3, 4);
        assertThat(s.countFor(5)).isEqualTo(4);
        assertThat(RatingSummary.ofCounts(1, 1, 1, 0, 0).average()).isEqualByComparingTo("2.00");
        assertThat(RatingSummary.ofCounts(0, 0, 1, 0, 2).average()).isEqualByComparingTo("4.33");
        assertThat(RatingSummary.ofCounts(0, 0, 0, 0, 1).average()).isEqualByComparingTo("5.00");
    }

    @Test
    void equalCountsGiveEqualSummariesSoUnchangedRatingsPublishNothing() {
        assertThat(RatingSummary.ofCounts(0, 1, 0, 0, 1)).isEqualTo(RatingSummary.ofCounts(0, 1, 0, 0, 1));
    }

    @Test
    void aBreakdownHasFiveStars() {
        assertThatThrownBy(() -> new RatingSummary(BigDecimal.ONE, 1, List.of(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
