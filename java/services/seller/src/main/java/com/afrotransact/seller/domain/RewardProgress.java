package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * One rule for one organisation: the metric value against the threshold.
 *
 * @param eligible   the rule applies to this organisation (live, kind, plan, not granted yet, not exhausted)
 * @param qualifies  eligible and the threshold is reached in an open window (or a closed sum window); for a rule
 *                   checked at window end, the check is due and the comparison holds
 * @param reason     why it is not eligible ("not on an eligible plan"…), else null
 * @param pending    a rule checked at window end whose check is still to come (eligible, before {@code checkAt})
 * @param checkAt    rules checked at window end: when the check runs, else null
 * @param closesAt   rules checked at window end: when the window closes, else null
 */
public record RewardProgress(RewardRule rule, long value, boolean eligible, boolean qualifies, boolean windowOpen,
                             String reason, boolean pending, Instant checkAt, Instant closesAt) {

    public RewardProgress(RewardRule rule, long value, boolean eligible, boolean qualifies, boolean windowOpen,
                          String reason) {
        this(rule, value, eligible, qualifies, windowOpen, reason, false, null, null);
    }

    /** Percent of the threshold, 0–100. */
    public int percent() {
        long t = rule.threshold();
        return t <= 0 ? 100 : (int) Math.min(100, value * 100 / t);
    }

    /** "$640 of $1,000 in sales → 1 month free"; "$640 in sales so far (less than $1,000 → 1 month free)". */
    public String label() {
        RewardMetric m = rule.metric();
        if (rule.comparison() == RewardComparison.LESS_THAN) {
            return m.format(value) + " " + m.noun() + " so far (less than " + m.format(rule.threshold()) + " → "
                    + rule.rewardLabel() + ")";
        }
        return m.format(Math.min(value, rule.threshold())) + " of " + m.format(rule.threshold()) + " " + m.noun()
                + " → " + rule.rewardLabel();
    }
}
