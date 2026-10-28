package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Locale;

/** Evaluates reward rules (pure: the caller reads the metric over {@link RewardWindow}). */
public final class RewardEvaluator {

    private RewardEvaluator() {
    }

    /**
     * @param alreadyGranted the organisation already has a grant of this rule (rewards are once per seller)
     */
    public static RewardProgress evaluate(RewardRule rule, SellerFacts facts, long value, RewardWindow window,
                                          boolean alreadyGranted, Instant now) {
        return evaluate(rule, facts, value, window, alreadyGranted, false, now);
    }

    /**
     * @param alreadyGranted the organisation already has a grant of this rule (rewards are once per seller)
     * @param alreadyChecked a rule checked at window end was already decided for this window (once per window)
     */
    public static RewardProgress evaluate(RewardRule rule, SellerFacts facts, long value, RewardWindow window,
                                          boolean alreadyGranted, boolean alreadyChecked, Instant now) {
        String reason = null;
        boolean closing = rule.checkedAtWindowEnd();
        if (!rule.isLive(now)) {
            reason = "Rule is not running";
        } else if (alreadyGranted) {
            reason = "Already granted";
        } else if (rule.exhausted()) {
            reason = "No grants left";
        } else if (rule.kind() != null && rule.kind() != facts.kind()) {
            reason = "For " + rule.kind().name().toLowerCase(Locale.ROOT) + "s only";
        } else if (facts.approvedAt() == null) {
            reason = "Not approved yet";
        } else if (!facts.paidSubscription()) {
            reason = "Not on a paid plan";
        } else if (!rule.eligiblePlans().isEmpty() && !rule.eligiblePlans().contains(facts.planCode())) {
            reason = "Not on an eligible plan";
        } else if (closing && facts.anchor(rule.windowAnchor()) == null) {
            reason = "Subscription not started";
        } else if (closing && alreadyChecked) {
            reason = "Already checked";
        } else if (closing && window.checkAt() != null && !now.isBefore(window.dueUntil())) {
            reason = "Window closed";
        }
        boolean eligible = reason == null;
        boolean holds = rule.comparison().test(value, rule.threshold());
        if (closing) {
            // Decided once: nothing qualifies before the check, and a missed check grants nothing.
            boolean due = window.due(now);
            return new RewardProgress(rule, value, eligible, eligible && due && holds, window.open(), reason,
                    eligible && !due && window.checkAt() != null && now.isBefore(window.checkAt()), window.checkAt(),
                    window.closesAt());
        }
        // A gauge must reach the threshold while the window is open; a sum counts only the window's days anyway.
        boolean inTime = window.open() || rule.metric().summed();
        return new RewardProgress(rule, value, eligible, eligible && holds && inTime, window.open(), reason);
    }
}
