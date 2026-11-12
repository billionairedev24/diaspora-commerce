package com.afrotransact.offers.application;

/**
 * Brings offers that wait for a tax decision to the state tax-returns supports (S384). It is the only component that moves an
 * offer from TAX_HELD to ACTIVE, and it does so only when tax-returns says the seller has a RESOLVED decision on the ATIN
 * right now (an event is a reason to ask, never the proof). It also checks offers that were live before the check existed.
 * Everything here is idempotent: an offer that is already where it should be is left alone.
 */
public interface TaxHoldService {

    /**
     * Re-checks every offer held on this variant (a decision was made or changed): the ones whose seller now has a RESOLVED
     * decision become ACTIVE. Returns how many were released.
     */
    int releaseFor(String atin);

    /**
     * One run of the safety net: re-checks held offers whose event was missed, stamps the active offers that have a
     * RESOLVED decision and holds the ones that have none. Looks at the batch size of the offer settings. Returns how many
     * offers changed.
     */
    int sweep();
}
