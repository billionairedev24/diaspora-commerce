package com.afrotransact.payments.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact integer apportionment (no cent is ever created or lost). */
public final class Apportionment {

    private Apportionment() {
    }

    /**
     * Splits {@code total} minor units in proportion to {@code weights} with the largest-remainder (Hamilton) method:
     * everyone gets the floor of their exact share, then the leftover cents go one by one to the largest remainders.
     * Ties — and the whole amount when every weight is zero — go to {@code preferred} (AfroTransact), then to the
     * lower index. The result always sums to {@code total}.
     */
    public static long[] largestRemainder(long total, long[] weights, int preferred) {
        if (total < 0) {
            long[] positive = largestRemainder(-total, weights, preferred);
            for (int i = 0; i < positive.length; i++) {
                positive[i] = -positive[i];
            }
            return positive;
        }
        if (weights.length == 0) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        if (preferred < 0 || preferred >= weights.length) {
            throw new IllegalArgumentException("preferred index out of range");
        }
        BigInteger sum = BigInteger.ZERO;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("Weights must be non-negative");
            }
            sum = sum.add(BigInteger.valueOf(w));
        }
        long[] shares = new long[weights.length];
        if (sum.signum() == 0) {
            shares[preferred] = total;
            return shares;
        }
        BigInteger bigTotal = BigInteger.valueOf(total);
        BigInteger[] remainders = new BigInteger[weights.length];
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] qr = bigTotal.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(sum);
            shares[i] = qr[0].longValueExact();
            remainders[i] = qr[1];
            allocated += shares[i];
        }
        long leftover = total - allocated;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                .thenComparing(i -> i == preferred ? 0 : 1)
                .thenComparing(i -> i));
        for (int k = 0; k < leftover; k++) {
            shares[order.get(k)]++;
        }
        return shares;
    }
}
