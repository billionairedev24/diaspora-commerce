package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The seller service's {@code seller.subscription.changed}: an organisation's plan changed (activation, upgrade, a
 * scheduled change applied, cancellation, staff migration…).
 *
 * @param eventId the event-id header; the inbox de-duplicates on it
 * @param status  the subscription status (TRIALING, ACTIVE, PAST_DUE, CANCELED, …)
 */
public record SubscriptionNotice(UUID eventId, String orgId, String planCode, String status, Instant occurredAt) {
}
