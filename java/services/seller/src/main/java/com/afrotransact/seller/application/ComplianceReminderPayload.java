package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

/** Payload of seller.compliance.reminder (scheduled 7/3/1 days before the deadline, or sent by staff). */
public record ComplianceReminderPayload(String orgId, String accountId, Integer thresholdDays, boolean manual,
                                        String actorId, List<String> openItems, Instant currentDeadline,
                                        List<String> channels, Instant occurredAt) {

    public ComplianceReminderPayload {
        openItems = List.copyOf(openItems);
        channels = List.copyOf(channels);
    }
}
