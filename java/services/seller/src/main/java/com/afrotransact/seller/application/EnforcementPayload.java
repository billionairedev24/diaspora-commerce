package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

/**
 * Payload of seller.payouts.paused / resumed (payments-service holds or releases payouts) and seller.listings.hidden /
 * restored (catalog, offers, bookings and the indexer hide or show the organisation's listings and services).
 *
 * @param reason AUTO_PAST_DUE, MANUAL, DEAUTHORIZED, CHARGES_DISABLED or CLEARED
 */
public record EnforcementPayload(String orgId, String accountId, String reason, String actorId, String note,
                                 List<String> pastDue, Instant occurredAt) {

    public EnforcementPayload {
        pastDue = List.copyOf(pastDue);
    }
}
