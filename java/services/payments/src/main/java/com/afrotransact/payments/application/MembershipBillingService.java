package com.afrotransact.payments.application;

/**
 * The scheduled side of memberships (S323): renewals and trial conversions with retries that end in a lapse, the
 * monthly credit, and the sweep that makes sure every delivered order earned its cashback. Every operation is
 * idempotent and safe on several replicas (rows are locked with SKIP LOCKED).
 */
public interface MembershipBillingService {

    /**
     * Processes up to {@code max} memberships whose period ended: a canceled one ends, complimentary days expire, a paid
     * one is charged for its next period (a scheduled plan change applies first). A failed charge makes the membership
     * PAST_DUE and is retried on the staff-set schedule; when the last retry fails it lapses.
     *
     * @return how many memberships were processed
     */
    int renewDue(int max);

    /** Grants this month's credit to members whose tier has a monthly credit; returns how many grants were made. */
    int grantMonthlyCredits(int max);

    /** Grants cashback on recently delivered orders of members that the delivery hook missed; returns how many. */
    int grantDueCashback(int max);
}
