package com.afrotransact.ai.domain;

/** Budget arithmetic: compares today's usage of a user and of a feature with the feature's limits. */
public interface BudgetPolicy {

    BudgetDecision decide(UsageTotals userToday, UsageTotals featureToday, BudgetLimits limits);
}
