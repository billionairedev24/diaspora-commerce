package com.afrotransact.payments.domain;

/**
 * Where a referral is.
 *
 * <ul>
 *   <li>PENDING: the friend signed up and has not had a qualifying order delivered yet</li>
 *   <li>WAITING: qualified; the return window runs, then the rewards are granted</li>
 *   <li>IN_REVIEW: an abuse check flagged it; staff approve or reject it</li>
 *   <li>PARTIALLY_REWARDED: one side is granted, the other waits (for example the membership program)</li>
 *   <li>REWARDED, REJECTED (staff or the system, with a reason), EXPIRED: final</li>
 * </ul>
 */
public enum ReferralStatus {
    PENDING,
    WAITING,
    IN_REVIEW,
    PARTIALLY_REWARDED,
    REWARDED,
    REJECTED,
    EXPIRED;

    public boolean isFinal() {
        return this == REWARDED || this == REJECTED || this == EXPIRED;
    }
}
