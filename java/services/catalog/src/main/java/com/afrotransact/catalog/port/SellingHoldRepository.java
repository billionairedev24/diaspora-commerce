package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Listings paused by a selling approval, and the lookups the pause and restore sweeps need. A hold is per product and
 * scope; a listing is resumed only when its last hold goes.
 */
public interface SellingHoldRepository {

    /** Records the hold (idempotent: keeps the first reason and time). */
    void hold(UUID productId, String scopeType, String scopeKey, String orgId, String reason, Instant at);

    boolean isHeld(UUID productId);

    /** Removes this scope's hold on the product; returns whether the product has no other hold left. */
    boolean release(UUID productId, String scopeType, String scopeKey);

    /** Product ids held for the scope (optionally one organisation), keyset by id. */
    List<UUID> heldFor(String scopeType, String scopeKey, String orgId, UUID afterId, int limit);

    /**
     * LIVE products of the scope, keyset by id: an organisation's (orgId set) or everybody's. {@code categoryCodes}
     * is used for a CAPABILITY scope (the categories it covers).
     */
    List<UUID> liveInScope(String scopeType, String scopeKey, Collection<String> categoryCodes, String orgId,
                           UUID afterId, int limit);
}
