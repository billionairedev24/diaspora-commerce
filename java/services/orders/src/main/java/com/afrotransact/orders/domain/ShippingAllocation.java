package com.afrotransact.orders.domain;

import java.util.Locale;

/**
 * How a package's delivery charge is spread over its lines for tax (rules TX-05 to TX-07): the delivery follows the
 * sale, so a delivery tied only to exempt lines is taxed at 0 % and a mixed package is split.
 *
 * <p><strong>OPEN LEGAL DECISION (category-tax-design.md, open decision 5):</strong> the attribution method for mixed
 * baskets must be approved by the tax adviser before production. {@link #PROPORTIONAL} (by net item value) is the
 * default build; {@link #FIRST_LINE} is the previous behaviour, kept as the rollback. The gateway-bff estimate uses
 * the same arithmetic (shared vectors in {@code tools/checkout/shipping-allocation-vectors.json}).
 */
public enum ShippingAllocation {

    /** Largest-remainder split by each line's net item value (items minus discount). */
    PROPORTIONAL,
    /** The whole charge on the package's first line. */
    FIRST_LINE;

    /** The method named by {@code at.orders.tax.shipping-allocation}; unknown or blank values mean the default. */
    public static ShippingAllocation parse(String value) {
        if (value == null || value.isBlank()) {
            return PROPORTIONAL;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "FIRST_LINE" -> FIRST_LINE;
            case "PROPORTIONAL" -> PROPORTIONAL;
            default -> throw new IllegalArgumentException("at.orders.tax.shipping-allocation must be PROPORTIONAL or "
                    + "FIRST_LINE, not " + value);
        };
    }

    /**
     * The shipping on each line; the amounts always add up to {@code totalMinor} (when it is positive and there is a
     * line).
     *
     * @param totalMinor   the package's delivery charge after waivers and shipping discounts
     * @param weightsMinor net item value of each line, in line order
     */
    public long[] allocate(long totalMinor, long[] weightsMinor) {
        int n = weightsMinor.length;
        long[] out = new long[n];
        if (totalMinor <= 0 || n == 0) {
            return out;
        }
        long sum = 0;
        for (long w : weightsMinor) {
            sum += Math.max(0, w);
        }
        if (this == FIRST_LINE || sum == 0) {
            out[0] = totalMinor;
            return out;
        }
        long[] remainders = new long[n];
        long given = 0;
        for (int i = 0; i < n; i++) {
            long product = Math.multiplyExact(totalMinor, Math.max(0, weightsMinor[i]));
            out[i] = product / sum;
            remainders[i] = product % sum;
            given += out[i];
        }
        for (long left = totalMinor - given; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            out[best]++;
            remainders[best] = -1;
        }
        return out;
    }
}
