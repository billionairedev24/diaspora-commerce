package com.afrotransact.ai.application;

import com.afrotransact.ai.domain.AiFeature;

/**
 * The gateway-wide spend ceiling, checked before every model call and before the per-feature budgets: the call rate of
 * the caller, the caller's daily share, and the daily total of the whole gateway. Throws {@code RateLimitedException}
 * (429 {@code ai.rate-limited}) when the caller is calling too fast and {@code BudgetExceededException} (429
 * {@code ai.budget-exceeded}) when the caller's share or the daily total is used up.
 */
public interface SpendGuard {

    void check(Caller caller, AiFeature feature);
}
