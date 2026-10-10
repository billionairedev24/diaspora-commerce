package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.SuspensionReason;

/**
 * Trust and Safety suspension of an organisation (permission sellers:enforce; staff or the trust-ops-service
 * client). Suspension is its own enforcement hold, separate from Stripe compliance payout holds: it pauses payouts,
 * hides listings and sets the organisation SUSPENDED. Reinstating releases only the suspension hold; payouts and
 * listings come back only when no compliance hold still requires them. Both calls are idempotent.
 */
public interface SellerEnforcementService {

    /**
     * Suspends an approved organisation. Publishes {@code seller.listings.hidden} and {@code seller.payouts.paused}
     * (reason SUSPENDED) and {@code seller.org.updated} (status SUSPENDED). An already suspended organisation is
     * returned unchanged.
     */
    EnforcementView suspend(String orgId, SuspensionReason reason, String note);

    /**
     * Lifts the active suspension and restores the previous organisation status ({@code seller.org.updated}).
     * Publishes {@code seller.payouts.resumed} / {@code seller.listings.restored} (reason REINSTATED) unless a
     * compliance hold still requires them. An organisation that is not suspended is returned unchanged.
     */
    EnforcementView reinstate(String orgId, String note);

    /** Current suspension and compliance holds, with the suspension history. */
    EnforcementView status(String orgId);
}
