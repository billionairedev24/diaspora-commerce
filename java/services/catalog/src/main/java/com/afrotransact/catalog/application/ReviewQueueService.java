package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One queue for the staff that review catalog requests (S386): change requests and no-barcode exemptions that wait for a
 * decision, oldest first, with age and SLA. Held images are listed by media-service, not here.
 */
public interface ReviewQueueService {

    /** Waiting items, oldest first (catalog:moderate); {@code kind} null for both kinds. */
    PageView<QueueItem> queue(Kind kind, int page, int size);

    enum Kind {
        CHANGE_REQUEST, GTIN_EXEMPTION
    }

    /**
     * @param summary the request type, or "No barcode"
     * @param overdue waiting past its SLA
     */
    record QueueItem(Kind kind, UUID id, String atin, String title, String summary, String requestedByOrg,
                     Instant createdAt, Instant slaDueAt, boolean overdue, long ageHours) {
    }
}
