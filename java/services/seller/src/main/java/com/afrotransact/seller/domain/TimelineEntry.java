package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One audited transition of an application: who, what, why (reason code + note) and when. */
public record TimelineEntry(UUID id, String applicationId, ReviewAction action, ApplicationStatus fromStatus,
                            ApplicationStatus toStatus, String actorId, String reasonCode, String note,
                            List<String> items, Instant occurredAt) {

    public TimelineEntry {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
