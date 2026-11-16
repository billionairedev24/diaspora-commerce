package com.afrotransact.ai.domain;

/**
 * The limits that apply on top of the per-feature budgets and the gateway-wide daily cap (which is a runtime setting read
 * through {@code AiSpendCapPolicy}, not part of this record): a daily share per caller so a single caller cannot use up
 * the day for everyone, and a per-minute call rate per caller. A value of 0 means "no limit".
 *
 * @param callerDailyCostMicros  micro-dollars one caller (user or service client) may spend per UTC day, all features
 * @param userCallsPerMinute     model calls per minute for a user token
 * @param serviceCallsPerMinute  model calls per minute for a service client (media-service, MCP server)
 */
public record SpendLimits(long callerDailyCostMicros, int userCallsPerMinute,
                          int serviceCallsPerMinute) {

    public SpendLimits {
        if (callerDailyCostMicros < 0 || userCallsPerMinute < 0 || serviceCallsPerMinute < 0) {
            throw new IllegalArgumentException("spend limits cannot be negative");
        }
    }

    public static SpendLimits unlimited() {
        return new SpendLimits(0, 0, 0);
    }

    /** Calls per minute that apply to a caller of the given principal type. */
    public int callsPerMinute(String principalType) {
        return "SERVICE".equals(principalType) ? serviceCallsPerMinute : userCallsPerMinute;
    }
}
