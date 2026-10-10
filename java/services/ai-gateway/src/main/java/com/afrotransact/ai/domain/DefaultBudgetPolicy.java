package com.afrotransact.ai.domain;

/**
 * Soft and hard limits. Below every limit: allow. At or above a limit: degrade to the cheapest model when the feature
 * allows it and usage is still below {@code hardLimitFactor × limit}; otherwise deny.
 */
public class DefaultBudgetPolicy implements BudgetPolicy {

    @Override
    public BudgetDecision decide(UsageTotals userToday, UsageTotals featureToday, BudgetLimits limits) {
        Check worst = Check.NONE;
        worst = worst.max(check("user-tokens", userToday.tokens(), limits.userDailyTokens(), limits));
        worst = worst.max(check("user-cost", userToday.costMicros(), limits.userDailyCostMicros(), limits));
        worst = worst.max(check("feature-cost", featureToday.costMicros(), limits.featureDailyCostMicros(), limits));
        return switch (worst.verdict) {
            case ALLOW -> BudgetDecision.ALLOW;
            case DEGRADE -> new BudgetDecision(BudgetDecision.Verdict.DEGRADE, worst.scope,
                    "Daily " + worst.scope + " budget reached; answering with the most economical model");
            case DENY -> new BudgetDecision(BudgetDecision.Verdict.DENY, worst.scope,
                    "Daily " + worst.scope + " budget exhausted; try again tomorrow");
        };
    }

    private static Check check(String scope, long used, long limit, BudgetLimits limits) {
        if (limit <= 0 || used < limit) {
            return Check.NONE;
        }
        double hard = limit * limits.hardLimitFactor();
        if (limits.degradeOnExceed() && used < hard) {
            return new Check(BudgetDecision.Verdict.DEGRADE, scope);
        }
        return new Check(BudgetDecision.Verdict.DENY, scope);
    }

    private record Check(BudgetDecision.Verdict verdict, String scope) {

        static final Check NONE = new Check(BudgetDecision.Verdict.ALLOW, "none");

        Check max(Check other) {
            return other.verdict.ordinal() > verdict.ordinal() ? other : this;
        }
    }
}
