package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Listings closed because a variant has no stored, resolved tax decision. The hold remembers that the listing was live,
 * so it comes back on its own when tax review approves it, and never when it was not live before (a new or imported
 * listing waits for a person). A hold is released, never deleted.
 */
public interface ProductTaxHoldRepository {

    /** Records (or reopens) the hold; the listing has not been announced yet. */
    void hold(UUID productId, String reasonCode, Instant at);

    /** True while the product has an open (not released) hold. */
    boolean isHeld(UUID productId);

    /** Releases the hold; returns whether there was an open one. */
    boolean release(UUID productId, Instant at);

    /** Open holds, keyset by product id. */
    List<UUID> openHolds(UUID afterId, int limit);

    /** Open holds whose closing was not published yet, oldest first. */
    List<Held> unannounced(int limit);

    void markAnnounced(UUID productId, Instant at);

    /** One open hold with the reason code it was recorded with. */
    record Held(UUID productId, String reasonCode) {
    }
}
