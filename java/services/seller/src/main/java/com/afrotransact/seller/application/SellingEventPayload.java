package com.afrotransact.seller.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Payload of the {@code seller.selling.*} events. {@code recipientUserIds} are the people to tell (the organisation's
 * owners for a seller notice, staff for an internal one); search and catalog read {@code orgId}, {@code scopeType}
 * and {@code scopeKey}.
 *
 * @param reason  why listings were paused (EXPIRED, PERFORMANCE, RULE) or what a notice is about
 * @param daysLeft days until the earliest document expires (expiry warnings)
 */
public record SellingEventPayload(String orgId, String scopeType, String scopeKey, String scopeLabel,
                                  String applicationId, String reference, String status, String reason, String note,
                                  List<String> requestedItems, boolean disqualified, LocalDate reapplyAfter,
                                  Instant expiresAt, Integer daysLeft, List<String> recipientUserIds,
                                  List<String> staffRecipientUserIds, Instant occurredAt) {

    public SellingEventPayload {
        requestedItems = requestedItems == null ? List.of() : List.copyOf(requestedItems);
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
        staffRecipientUserIds = staffRecipientUserIds == null ? List.of() : List.copyOf(staffRecipientUserIds);
    }
}
