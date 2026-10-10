package com.afrotransact.ai.domain;

/**
 * Daily limits for one feature. A value of 0 means "no limit".
 *
 * @param userDailyTokens       tokens one user may spend per UTC day on this feature
 * @param userDailyCostMicros   micro-dollars one user may spend per UTC day on this feature
 * @param featureDailyCostMicros micro-dollars all users together may spend per UTC day on this feature
 * @param degradeOnExceed       above a limit, serve with the cheapest model instead of refusing …
 * @param hardLimitFactor       … until usage reaches this multiple of the limit (for example 1.5), then refuse
 */
public record BudgetLimits(long userDailyTokens, long userDailyCostMicros, long featureDailyCostMicros,
                           boolean degradeOnExceed, double hardLimitFactor) {

    public BudgetLimits {
        if (userDailyTokens < 0 || userDailyCostMicros < 0 || featureDailyCostMicros < 0) {
            throw new IllegalArgumentException("budget limits cannot be negative");
        }
        if (hardLimitFactor < 1.0) {
            throw new IllegalArgumentException("hardLimitFactor must be at least 1.0");
        }
    }

    public static BudgetLimits unlimited() {
        return new BudgetLimits(0, 0, 0, false, 1.0);
    }
}
