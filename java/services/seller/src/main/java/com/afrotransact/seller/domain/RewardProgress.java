package com.afrotransact.seller.domain;

/**
 * One rule for one organisation: the metric value against the threshold.
 *
 * @param eligible   the rule applies to this organisation (live, kind, plan, not granted yet, not exhausted)
 * @param qualifies  eligible and the threshold is reached in an open window (or a closed sum window)
 * @param reason     why it is not eligible ("not on an eligible plan"…), else null
 */
public record RewardProgress(RewardRule rule, long value, boolean eligible, boolean qualifies, boolean windowOpen,
                             String reason) {

    /** Percent of the threshold, 0–100. */
    public int percent() {
        long t = rule.threshold();
        return t <= 0 ? 100 : (int) Math.min(100, value * 100 / t);
    }

    /** "$640 of $1,000 in sales → 1 month free". */
    public String label() {
        RewardMetric m = rule.metric();
        return m.format(Math.min(value, rule.threshold())) + " of " + m.format(rule.threshold()) + " " + m.noun()
                + " → " + rule.rewardLabel();
    }
}
