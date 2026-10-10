package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A late provider cancellation from bookings-service ({@code booking.cancelled} with {@code cancelledBy = PROVIDER}
 * and {@code providerFeeMinor > 0} on {@code at.bookings.events}).
 *
 * @param eventId          the event-id header; the inbox de-duplicates on it
 * @param bookingReference BK-… (the order id of the booking's payment)
 */
public record ProviderFeeNotice(UUID eventId, String bookingId, String bookingReference, String orgId,
                                long amountMinor, String currency, Instant occurredAt) {
}
