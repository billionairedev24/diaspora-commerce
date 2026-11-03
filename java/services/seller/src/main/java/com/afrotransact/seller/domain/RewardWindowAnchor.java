package com.afrotransact.seller.domain;

/**
 * Where a rule's "first N days" start: at final approval, or at the start of the subscription (the first day of its
 * trial, when it has one).
 */
public enum RewardWindowAnchor {
    APPROVAL,
    SUBSCRIPTION_START
}
