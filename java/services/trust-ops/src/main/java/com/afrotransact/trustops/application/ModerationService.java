package com.afrotransact.trustops.application;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.ListingReport;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.port.Page;

/**
 * S117 listing moderation queue: SLA timers, assignment, approve / reject with reason codes, escalation. Holds new
 * submissions (NEW_LISTING) and, since S228, reported or flagged live listings (LIVE_LISTING).
 */
public interface ModerationService {

    /**
     * Queues a product submitted for review ({@code catalog.product.created} with status IN_REVIEW). Asks AI triage
     * for a hint first; when AI is off or fails the item is queued without one. Re-deliveries are ignored.
     */
    Optional<ModerationItemView> enqueue(UUID eventId, ProductSubmission submission);

    /**
     * Queues a reported or flagged live listing ({@code catalog.product.flagged}) with AI triage (fail-open). When the
     * product already has an open item, the report is counted on it (a high-risk flag raises its priority) instead.
     * Re-deliveries are ignored.
     */
    Optional<ModerationItemView> enqueueLiveListing(UUID eventId, ProductSubmission listing, ListingReport report);

    /**
     * Catalog decided the product directly (approved, rejected or taken down there): closes its open queue item, if
     * any.
     */
    void closeFromCatalog(UUID eventId, UUID productId, String catalogStatus);

    /**
     * Queue, soonest due first. {@code status} null = open items; {@code mine} = assigned to the caller;
     * {@code type} null = new and live listings.
     */
    Page<ModerationItemView> queue(ModerationStatus status, String assignee, boolean mine, ModerationItemType type,
                                   int page, int size);

    ModerationItemView get(UUID id);

    /** Assigns to {@code assignee}, or to the caller when null. */
    ModerationItemView assign(UUID id, String assignee);

    ModerationItemView escalate(UUID id, String reason);

    /**
     * Allows the listing, then closes the item. Audited. A new submission is approved in catalog
     * ({@code POST /v1/products/{id}/approve}); a live listing simply stays live (the reports are dismissed).
     */
    ModerationItemView approve(UUID id, String note);

    /**
     * Removes the listing with the reason code and note, then closes the item. Audited. A new submission is rejected
     * in catalog ({@code /reject}); a live listing is taken down ({@code POST /v1/products/{id}/takedown}).
     */
    ModerationItemView reject(UUID id, ModerationReasonCode reasonCode, String note);

    /** Re-runs AI triage (fail-open: the item is returned unchanged when AI is unavailable). */
    ModerationItemView retriage(UUID id);
}
