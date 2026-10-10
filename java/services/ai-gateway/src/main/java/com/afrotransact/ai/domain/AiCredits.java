package com.afrotransact.ai.domain;

/**
 * AI credits billed to seller and provider organisations (S206): one credit is {@value #MICROS_PER_CREDIT}
 * micro-dollars (one US cent) of estimated model cost. A day's credits round up, so any usage costs at least one.
 */
public final class AiCredits {

    public static final long MICROS_PER_CREDIT = 10_000L;

    private AiCredits() {
    }

    /** Credits for {@code costMicros} of estimated cost, rounded up; zero cost is zero credits. */
    public static long fromCostMicros(long costMicros) {
        if (costMicros < 0) {
            throw new IllegalArgumentException("cost cannot be negative");
        }
        return (costMicros + MICROS_PER_CREDIT - 1) / MICROS_PER_CREDIT;
    }
}
