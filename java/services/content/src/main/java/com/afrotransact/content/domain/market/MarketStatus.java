package com.afrotransact.content.domain.market;

/** Country status in Markets & locations. Only LIVE countries take orders and seller onboarding. */
public enum MarketStatus {
    LIVE,
    PAUSED,
    COMING_SOON,
    PLANNED
}
