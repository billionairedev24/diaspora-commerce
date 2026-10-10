package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.util.List;

/** Splits an amount in minor units across lines in proportion to their weights, summing exactly to the amount. */
public interface CostAllocator {

    /**
     * @param amountMinor non-negative amount to split
     * @param weights     one non-negative weight per line, at least one positive
     * @return one share per line, in the same order; shares are non-negative and sum to {@code amountMinor}
     */
    List<Long> allocate(long amountMinor, List<BigDecimal> weights);
}
