package com.afrotransact.ai.domain;

/**
 * The spend ceiling that applies on top of the per-feature budgets: one daily total for the whole gateway (the owner's
 * hard cap), a daily share per caller so a single caller cannot use up the day for everyone, and a per-minute call
 * rate per caller. A value of 0 means "no limit".
 *
 * @param globalDailyCostMicros  micro-dollars all callers and features together may spend per UTC day
 * @param callerDailyCostMicros  micro-dollars one caller (user or service client) may spend per UTC day, all features
 * @param userCallsPerMinute     model calls per minute for a user token
 * @param serviceCallsPerMinute  model calls per minute for a service client (media-service, MCP server)
 */
public record SpendLimits(long globalDailyCostMicros, long callerDailyCostMicros, int userCallsPerMinute,
                          int serviceCallsPerMinute) {

    public SpendLimits {
        if (globalDailyCostMicros < 0 || callerDailyCostMicros < 0 || userCallsPerMinute < 0
                || serviceCallsPerMinute < 0) {
            throw new IllegalArgumentException("spend limits cannot be negative");
        }
    }

    public static SpendLimits unlimited() {
        return new SpendLimits(0, 0, 0, 0);
    }

    /** Calls per minute that apply to a caller of the given principal type. */
    public int callsPerMinute(String principalType) {
        return "SERVICE".equals(principalType) ? serviceCallsPerMinute : userCallsPerMinute;
    }
}
