package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/** One change of a connected account (Stripe event, seller answer, staff action) with the resulting snapshot. */
public record ComplianceHistoryEntry(UUID id, String accountId, String source, String eventId, String eventType,
                                     ComplianceStatus status, String summary, RequirementsSnapshot snapshot,
                                     String actorId, Instant occurredAt) {
}
