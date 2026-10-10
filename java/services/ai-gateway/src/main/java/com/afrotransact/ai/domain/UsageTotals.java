package com.afrotransact.ai.domain;

/** Tokens and estimated cost (micro-dollars) spent so far today. */
public record UsageTotals(long tokens, long costMicros) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0);

    public UsageTotals {
        if (tokens < 0 || costMicros < 0) {
            throw new IllegalArgumentException("usage cannot be negative");
        }
    }
}
