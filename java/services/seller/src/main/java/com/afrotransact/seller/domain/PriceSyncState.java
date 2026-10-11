package com.afrotransact.seller.domain;

/**
 * Drift between a plan version's price and Stripe.
 * <ul>
 *   <li>{@code IN_SYNC}: the stored Stripe Price matches (amount, currency, interval) and is active.</li>
 *   <li>{@code FREE}: nothing to sync (free plan).</li>
 *   <li>{@code ADOPTABLE}: no stored id, but Stripe has a matching active Price under the lookup key (made by
 *       {@code make stripe-setup}); the sync adopts it.</li>
 *   <li>{@code MISSING}: no Stripe Price yet; the sync creates one.</li>
 *   <li>{@code DRIFT}: the stored or looked-up Price differs, or was archived while current; the sync creates a new
 *       Price, moves the lookup key and archives the old one.</li>
 *   <li>{@code SUPERSEDED}: an older version; its Price keeps billing its subscribers and is not touched.</li>
 * </ul>
 */
public enum PriceSyncState {
    IN_SYNC,
    FREE,
    ADOPTABLE,
    MISSING,
    DRIFT,
    SUPERSEDED;

    /** Decides the state of a current version's price from what Stripe answered. */
    public static PriceSyncState of(long expectedCents, Cadence cadence, String storedPriceId,
                                    StripePriceFacts stored, StripePriceFacts byLookupKey) {
        if (expectedCents == 0) {
            return FREE;
        }
        if (storedPriceId != null) {
            return stored != null && stored.active() && stored.matches(expectedCents, cadence) ? IN_SYNC : DRIFT;
        }
        if (byLookupKey == null) {
            return MISSING;
        }
        return byLookupKey.active() && byLookupKey.matches(expectedCents, cadence) ? ADOPTABLE : DRIFT;
    }
}
