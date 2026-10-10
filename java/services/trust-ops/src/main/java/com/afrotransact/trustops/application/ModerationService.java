package com.afrotransact.trustops.application;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.port.Page;

/** S117 listing moderation queue: SLA timers, assignment, approve / reject with reason codes, escalation. */
public interface ModerationService {

    /**
     * Queues a product submitted for review ({@code catalog.product.created} with status IN_REVIEW). Asks AI triage
     * for a hint first; when AI is off or fails the item is queued without one. Re-deliveries are ignored.
     */
    Optional<ModerationItemView> enqueue(UUID eventId, ProductSubmission submission);

    /** Catalog approved / rejected the product directly: closes its open queue item, if any. */
    void closeFromCatalog(UUID eventId, UUID productId, String catalogStatus);

    /** Queue, soonest due first. {@code status} null = open items; {@code mine} = assigned to the caller. */
    Page<ModerationItemView> queue(ModerationStatus status, String assignee, boolean mine, int page, int size);

    ModerationItemView get(UUID id);

    /** Assigns to {@code assignee}, or to the caller when null. */
    ModerationItemView assign(UUID id, String assignee);

    ModerationItemView escalate(UUID id, String reason);

    /** Approves in catalog ({@code POST /v1/products/{id}/approve}), then closes the item. Audited. */
    ModerationItemView approve(UUID id, String note);

    /** Rejects in catalog with the reason code and note, then closes the item. Audited. */
    ModerationItemView reject(UUID id, ModerationReasonCode reasonCode, String note);

    /** Re-runs AI triage (fail-open: the item is returned unchanged when AI is unavailable). */
    ModerationItemView retriage(UUID id);
}
