package com.afrotransact.offers.domain;

/** Lifecycle of an offer: the seller's choices plus the tax hold the platform puts on it (S384). */
public enum OfferStatus {
    ACTIVE,
    PAUSED,
    WITHDRAWN,
    /**
     * The offering seller has no RESOLVED tax decision on this ATIN yet: not buyable, not in search, not in the buy box.
     * Only the tax release component brings it to ACTIVE.
     */
    TAX_HELD
}
