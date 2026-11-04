package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One audited transition of an application: who, what, why (reason code + note) and when.
 *
 * @param actorName  the staff member's display name at the time (null for the applicant and the system)
 * @param requestKey the decision's Idempotency-Key: a retry with the same key replays this entry
 */
public record TimelineEntry(UUID id, String applicationId, ReviewAction action, ApplicationStatus fromStatus,
                            ApplicationStatus toStatus, String actorId, String reasonCode, String note,
                            List<String> items, Instant occurredAt, String actorName, String requestKey) {

    public TimelineEntry {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public TimelineEntry(UUID id, String applicationId, ReviewAction action, ApplicationStatus fromStatus,
                         ApplicationStatus toStatus, String actorId, String reasonCode, String note,
                         List<String> items, Instant occurredAt) {
        this(id, applicationId, action, fromStatus, toStatus, actorId, reasonCode, note, items, occurredAt, null,
                null);
    }

    /** Whether this entry is {@code action} by {@code actor} that put the application in {@code current}. */
    public boolean decided(ReviewAction action, String actor, ApplicationStatus current) {
        return this.action == action && actorId != null && actorId.equals(actor) && toStatus == current;
    }
}
