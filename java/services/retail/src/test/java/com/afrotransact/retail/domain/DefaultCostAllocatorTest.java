package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DefaultCostAllocatorTest {

    private final CostAllocator allocator = new DefaultCostAllocator();

    private static List<BigDecimal> w(long... weights) {
        List<BigDecimal> out = new ArrayList<>();
        for (long x : weights) {
            out.add(BigDecimal.valueOf(x));
        }
        return out;
    }

    @Test
    void remainderOfEqualSplitGoesToTheLastLine() {
        assertThat(allocator.allocate(100, w(1, 1, 1))).containsExactly(33L, 33L, 34L);
        assertThat(allocator.allocate(2, w(1, 1, 1))).containsExactly(0L, 0L, 2L);
        assertThat(allocator.allocate(1, w(1, 1))).containsExactly(0L, 1L);
    }

    @Test
    void exactProportionsNeedNoRemainder() {
        assertThat(allocator.allocate(1000, w(1, 3, 6))).containsExactly(100L, 300L, 600L);
        assertThat(allocator.allocate(485_000, w(744_000, 495_000, 405_000, 260_000)))
                .containsExactly(189_516L, 126_089L, 103_164L, 66_231L);
    }

    @Test
    void sharesAlwaysSumToTheAmount() {
        List<Long> shares = allocator.allocate(485_000, w(744_000, 495_000, 405_000, 260_000));
        assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(485_000);
    }

    @Test
    void neverProducesNegativeSharesWhenTheLastWeightIsSmall() {
        // Half-up rounding would give 2,2,2,2 and leave −1 for the last line; floor rounding cannot.
        List<Long> shares = allocator.allocate(7, w(10, 10, 10, 10, 1));
        assertThat(shares).containsExactly(1L, 1L, 1L, 1L, 3L);
        assertThat(shares).allMatch(s -> s >= 0);
    }

    @Test
    void zeroWeightLinesGetNothingAndTheRemainderGoesToTheLastWeightedLine() {
        assertThat(allocator.allocate(10, w(1, 1, 1, 0))).containsExactly(3L, 3L, 4L, 0L);
        assertThat(allocator.allocate(10, w(0, 5, 0))).containsExactly(0L, 10L, 0L);
    }

    @Test
    void singleLineTakesEverything() {
        assertThat(allocator.allocate(12_345, w(7))).containsExactly(12_345L);
    }

    @Test
    void zeroAmountAllocatesZeros() {
        assertThat(allocator.allocate(0, w(3, 4))).containsExactly(0L, 0L);
        assertThat(allocator.allocate(0, w(0, 0))).containsExactly(0L, 0L);
    }

    @Test
    void rejectsAllZeroWeightsForAPositiveAmount() {
        assertThatThrownBy(() -> allocator.allocate(5, w(0, 0)))
                .isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.allocation-basis-empty");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> allocator.allocate(-1, w(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(1, w(1, -1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsFractionalWeightsAndHugeAmountsWithoutOverflow() {
        List<BigDecimal> weights = List.of(new BigDecimal("0.333"), new BigDecimal("0.333"), new BigDecimal("0.334"));
        assertThat(allocator.allocate(1_000, weights)).containsExactly(333L, 333L, 334L);
        long huge = Long.MAX_VALUE / 2;
        List<Long> shares = allocator.allocate(huge, w(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 3));
        assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(huge);
    }

    @Test
    void isDeterministic() {
        List<BigDecimal> weights = w(17, 29, 31, 5, 101);
        assertThat(allocator.allocate(99_999, weights)).isEqualTo(allocator.allocate(99_999, weights));
    }

    @ParameterizedTest
    @CsvSource({"100,1,1,1", "1,1,1,1", "999,2,3,5", "1000000,7,11,13", "5,1000,1,1"})
    void eachNonLastShareIsTheFloorOfItsExactShare(long amount, long a, long b, long c) {
        List<BigDecimal> weights = w(a, b, c);
        List<Long> shares = allocator.allocate(amount, weights);
        BigDecimal total = BigDecimal.valueOf(a + b + c);
        for (int i = 0; i < 2; i++) {
            BigDecimal exact = BigDecimal.valueOf(amount).multiply(weights.get(i)).divide(total, 0, RoundingMode.DOWN);
            assertThat(shares.get(i)).isEqualTo(exact.longValueExact());
        }
    }

    @Test
    void randomisedInvariants() {
        Random random = new Random(20261010L);
        for (int run = 0; run < 2_000; run++) {
            int n = 1 + random.nextInt(12);
            long amount = random.nextInt(3) == 0 ? random.nextInt(10) : Math.abs(random.nextLong() % 10_000_000_000L);
            List<BigDecimal> weights = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                weights.add(BigDecimal.valueOf(random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(1_000_000)));
            }
            if (weights.stream().allMatch(x -> x.signum() == 0)) {
                weights.set(n - 1, BigDecimal.ONE);
            }
            List<Long> shares = allocator.allocate(amount, weights);
            BigDecimal total = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(shares).hasSize(n).allMatch(s -> s >= 0);
            assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(amount);
            for (int i = 0; i < n; i++) {
                BigDecimal exact = BigDecimal.valueOf(amount).multiply(weights.get(i))
                        .divide(total, 6, RoundingMode.HALF_UP);
                if (weights.get(i).signum() == 0) {
                    assertThat(shares.get(i)).isZero();
                }
                // No line is off its exact share by more than one minor unit per line.
                assertThat(BigDecimal.valueOf(shares.get(i)).subtract(exact).abs())
                        .isLessThanOrEqualTo(BigDecimal.valueOf(n));
            }
        }
    }
}
