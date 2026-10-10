package com.afrotransact.payments.application;

/**
 * Holds and releases an organisation's payouts when seller-service enforces Stripe compliance
 * (topic {@code at.seller-compliance.events}). Idempotent per event id; older decisions never override newer ones.
 */
public interface SellerEnforcementService {

    /** {@code seller.payouts.paused}. Returns false for a redelivered, stale or unusable event. */
    boolean payoutsPaused(EnforcementNotice notice);

    /** {@code seller.payouts.resumed}. Returns false for a redelivered, stale or unusable event. */
    boolean payoutsResumed(EnforcementNotice notice);
}
