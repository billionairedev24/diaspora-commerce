package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic proportional allocation with BigDecimal: every line except the last positively-weighted one gets
 * {@code floor(amount × weight / total)}; the remainder (always ≥ 0, below one minor unit per line) goes to the last
 * positively-weighted line, so shares always sum exactly to the amount and the result never depends on ordering
 * tricks or floating point. Lines with zero weight get nothing.
 */
public class DefaultCostAllocator implements CostAllocator {

    @Override
    public List<Long> allocate(long amountMinor, List<BigDecimal> weights) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Amount to allocate cannot be negative");
        }
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("Nothing to allocate to");
        }
        BigDecimal total = BigDecimal.ZERO;
        int last = -1;
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal w = weights.get(i);
            if (w == null || w.signum() < 0) {
                throw new IllegalArgumentException("Allocation weights must be non-negative");
            }
            if (w.signum() > 0) {
                last = i;
            }
            total = total.add(w);
        }
        if (total.signum() == 0) {
            if (amountMinor == 0) {
                return weights.stream().map(w -> 0L).toList();
            }
            throw new RetailRuleViolation("retail.allocation-basis-empty",
                    "Cannot allocate: every line has zero value, weight or quantity for this basis");
        }
        BigDecimal amount = BigDecimal.valueOf(amountMinor);
        List<Long> shares = new ArrayList<>(weights.size());
        long allocated = 0;
        for (int i = 0; i < weights.size(); i++) {
            if (i == last) {
                shares.add(0L);
                continue;
            }
            long share = amount.multiply(weights.get(i)).divide(total, 0, RoundingMode.DOWN).longValueExact();
            shares.add(share);
            allocated += share;
        }
        shares.set(last, amountMinor - allocated);
        return List.copyOf(shares);
    }
}
