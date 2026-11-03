package com.afrotransact.seller.domain;

/**
 * How a reward rule compares its metric with the threshold: {@code AT_LEAST} rewards reaching it (granted as soon as
 * it is reached), {@code LESS_THAN} rewards staying under it (low-sales relief: decided once, when the window closes).
 */
public enum RewardComparison {
    AT_LEAST,
    LESS_THAN;

    public boolean test(long value, long threshold) {
        return this == AT_LEAST ? value >= threshold : value < threshold;
    }
}
