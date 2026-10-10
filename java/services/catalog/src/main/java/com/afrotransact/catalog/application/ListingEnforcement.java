package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A seller-service {@code seller.listings.hidden} / {@code seller.listings.restored} event.
 *
 * @param eventId the event-id header; the inbox de-duplicates on it
 * @param reason  AUTO_PAST_DUE, MANUAL, DEAUTHORIZED, CHARGES_DISABLED or CLEARED
 */
public record ListingEnforcement(UUID eventId, String orgId, String reason, Instant occurredAt) {
}
