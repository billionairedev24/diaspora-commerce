package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A seller-service compliance enforcement event ({@code seller.payouts.paused} / {@code seller.payouts.resumed}).
 *
 * @param eventId the event-id header; the inbox de-duplicates on it
 * @param reason  AUTO_PAST_DUE, MANUAL, DEAUTHORIZED, CHARGES_DISABLED or CLEARED
 */
public record EnforcementNotice(UUID eventId, String orgId, String accountId, String reason, String actorId,
                                String note, Instant occurredAt) {
}
