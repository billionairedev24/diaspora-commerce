package com.afrotransact.seller.domain;

/** Reward rules are drafted, activated, paused and ended by staff; only ACTIVE rules grant. */
public enum RewardRuleStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ENDED
}
