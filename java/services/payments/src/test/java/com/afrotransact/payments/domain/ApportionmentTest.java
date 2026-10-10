package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ApportionmentTest {

    @Test
    void largestRemainderGivesLeftoverCentsToLargestRemainders() {
        // 100 split 1:1:1 → 33.33 each; one leftover cent goes to the preferred (index 0) on the tie.
        assertThat(Apportionment.largestRemainder(100, new long[] {1, 1, 1}, 0)).containsExactly(34, 33, 33);
        assertThat(Apportionment.largestRemainder(100, new long[] {1, 1, 1}, 2)).containsExactly(33, 33, 34);
        // 10 split 3:3:4 exactly
        assertThat(Apportionment.largestRemainder(10, new long[] {3, 3, 4}, 0)).containsExactly(3, 3, 4);
        // 7 split 0:1:2 → 2.33 / 4.67 → floors 2 / 4, the cent goes to the larger remainder (index 2)
        assertThat(Apportionment.largestRemainder(7, new long[] {0, 1, 2}, 0)).containsExactly(0, 2, 5);
    }

    @Test
    void zeroWeightsGiveEverythingToPreferred() {
        assertThat(Apportionment.largestRemainder(55, new long[] {0, 0}, 1)).containsExactly(0, 55);
    }

    @Test
    void alwaysSumsToTotalAndStaysWithinOneCentOfExact() {
        Random random = new Random(42);
        for (int run = 0; run < 20_000; run++) {
            int n = 1 + random.nextInt(8);
            long[] weights = new long[n];
            for (int i = 0; i < n; i++) {
                weights[i] = random.nextInt(4) == 0 ? 0 : random.nextInt(1_000_000);
            }
            long total = random.nextInt(100_000);
            long[] shares = Apportionment.largestRemainder(total, weights, random.nextInt(n));
            assertThat(Arrays.stream(shares).sum()).isEqualTo(total);
            long sum = Arrays.stream(weights).sum();
            if (sum > 0) {
                for (int i = 0; i < n; i++) {
                    double exact = (double) total * weights[i] / sum;
                    assertThat(Math.abs(shares[i] - exact)).isLessThan(1.0);
                }
            }
        }
    }
}
