package com.afrotransact.bookings.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumes seller-service enforcement ({@code at.seller-compliance.events}): {@code seller.listings.hidden} hides an
 * organisation's services and slots from the public endpoints (and new bookings); {@code seller.listings.restored}
 * brings them back. Existing bookings are untouched. Each event is applied once (inbox de-duplication).
 */
public interface SellerComplianceIngest {

    String CONSUMER = "bookings.seller-compliance";
    String LISTINGS_HIDDEN = "seller.listings.hidden";
    String LISTINGS_RESTORED = "seller.listings.restored";

    /** @return true when the event changed the organisation's visibility */
    boolean handle(UUID eventId, String eventType, String orgId, String reason, Instant occurredAt);
}
