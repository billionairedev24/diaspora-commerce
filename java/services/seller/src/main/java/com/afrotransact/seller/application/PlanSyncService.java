package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.Cadence;

/**
 * Keeps Stripe's Products and Prices in line with our plan catalogue: one Product per plan, one Price per plan
 * version and cadence. Idempotent (Stripe idempotency keys derive from plan, version, cadence and amount); a Price is
 * never edited: a different amount gets a new Price (the {@code <plan>_<cadence>} lookup key moves to it) and the old
 * one is archived. Prices made earlier by {@code make stripe-setup} are adopted by lookup key when amount, currency
 * and interval match.
 */
public interface PlanSyncService {

    /** The Stripe Price of a plan version and cadence, adopted or created on first use. */
    String ensurePrice(String planCode, int version, Cadence cadence);

    /** What a sync would do, per current price (staff with plans:manage / seller:billing; no Stripe writes). */
    PlanSyncReport report();

    /** Syncs every published plan's current version (staff "Sync with Stripe"). */
    PlanSyncReport sync();

    /** Startup reconcile (no caller): same as {@link #sync()}, errors are logged and reported per price. */
    PlanSyncReport reconcile();

    /** Syncs one plan after it was published (product name and the current version's prices). */
    PlanSyncReport syncPlan(String planCode);
}
