package com.afrotransact.payments.domain;

/**
 * Where a membership is. TRIALING, ACTIVE and PAST_DUE are live (the member has the benefits; past due is the grace
 * while failed renewals are retried); the others ended.
 */
public enum MembershipStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    /** The member canceled and the period ran out (or a refund ended it). */
    CANCELED,
    /** Renewals kept failing: the membership ended without the member asking. */
    LAPSED,
    /** Complimentary days ran out. */
    EXPIRED;

    public boolean live() {
        return this == TRIALING || this == ACTIVE || this == PAST_DUE;
    }
}
