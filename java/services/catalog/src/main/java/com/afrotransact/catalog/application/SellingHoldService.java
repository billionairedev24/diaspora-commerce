package com.afrotransact.catalog.application;

import java.util.List;

/**
 * Reacts to seller-service's selling events: pauses an organisation's listings in a scope when its approval expired (after
 * the grace), was revoked or paused by staff; restores them when the approval is renewed; and, when a rule changes, pauses
 * every live listing in the scope whose seller no longer qualifies and restores those that now do. Listings are paused
 * (status PAUSED, with a hold that says why), never deleted, and a listing the seller paused themselves stays paused.
 * Idempotent: a redelivered event changes nothing.
 */
public interface SellingHoldService {

    /** @return listings paused */
    int pause(Change change);

    /** @return listings resumed */
    int restore(Change change);

    /** @return listings paused plus resumed */
    int ruleChanged(Change change);

    /**
     * @param orgId         the organisation (null for a rule change, which concerns everybody)
     * @param reason        EXPIRED, PERFORMANCE, REVOKED, RULE, RENEWED
     * @param categoryCodes for a CAPABILITY scope: the categories it covers
     */
    record Change(String orgId, String scopeType, String scopeKey, String reason, List<String> categoryCodes) {

        public Change {
            categoryCodes = categoryCodes == null ? List.of() : List.copyOf(categoryCodes);
        }
    }
}
