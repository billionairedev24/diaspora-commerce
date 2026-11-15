package com.afrotransact.ai.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Today's AI spend (UTC day) against the daily cap, by feature: what the staff console shows on Settings > AI and MCP spend cap.
 * Staff with {@code ai:manage} only. Every figure comes from the usage ledger; a day without usage is reported as zero with
 * {@code hasData = false}, never as an estimate.
 */
public interface SpendTodayService {

    SpendToday today(Caller caller);

    /**
     * @param day             the UTC day
     * @param capMicros       the gateway-wide daily cap in micro-dollars (0 = unlimited); set by {@code AI_GLOBAL_DAILY_COST_USD}
     * @param callerCapMicros one caller's daily share in micro-dollars (0 = unlimited); {@code AI_CALLER_DAILY_COST_USD}
     * @param spentMicros     estimated model cost so far today, all features
     * @param hasData         at least one model call was recorded today
     * @param resetsAt        the next UTC midnight, when the day's total starts again
     */
    record SpendToday(LocalDate day, long capMicros, long callerCapMicros, long spentMicros, long calls, long tokens,
                      boolean hasData, Instant resetsAt, List<FeatureSpend> byFeature) {
    }

    /** One feature's spend today. */
    record FeatureSpend(String feature, long calls, long tokens, long costMicros) {
    }
}
