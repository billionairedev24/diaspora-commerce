package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An item in the moderation queue with its SLA timer, assignee, AI triage hint and the human decision.
 * {@code PENDING → ESCALATED → APPROVED | REJECTED}, or {@code CLOSED} when the owning service decided it
 * elsewhere.
 * {@link ModerationItemType#NEW_LISTING} items are submissions (approve / reject in catalog);
 * {@link ModerationItemType#LIVE_LISTING} items are reported or flagged live listings (keep live, or take down);
 * {@link ModerationItemType#SERVICE_REVIEW} items are customer reviews of bookings (publish or reject in bookings).
 *
 * @param product the listing (listing items; null for reviews)
 * @param report  why a live listing is queued (null for new submissions and reviews)
 * @param review  the review (review items; null for listings)
 */
public record ModerationItem(UUID id, ProductSubmission product, ModerationItemType type, ListingReport report,
                             ReviewSubmission review, ModerationStatus status, ModerationPriority priority,
                             TriageStatus triageStatus, TriageHint triage, Instant enqueuedAt, Sla sla,
                             String assignee, Instant assignedAt, boolean escalated, String escalationReason,
                             Instant escalatedAt, String decidedBy, Instant decidedAt, ModerationReasonCode reasonCode,
                             String decisionNote, long version) {

    public ModerationItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (type.listing()) {
            Objects.requireNonNull(product, "product");
        } else {
            Objects.requireNonNull(review, "review");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(triageStatus, "triageStatus");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        Objects.requireNonNull(sla, "sla");
    }

    /** Queues a submission; a high-risk AI hint raises the priority and shortens the SLA. */
    public static ModerationItem queue(UUID id, ProductSubmission product, TriageHint triage, Instant now,
                                       SlaPolicy sla) {
        ModerationPriority priority = triage != null && triage.highRisk() ? ModerationPriority.HIGH
                : ModerationPriority.NORMAL;
        return new ModerationItem(id, product, ModerationItemType.NEW_LISTING, null, null, ModerationStatus.PENDING,
                priority,
                triage == null ? TriageStatus.UNAVAILABLE : TriageStatus.COMPLETED, triage, now,
                sla.moderation(now, priority), null, null, false, null, null, null, null, null, null, 0);
    }

    /**
     * Queues a reported or flagged live listing. A high-risk AI hint or a high-risk automated flag raises the
     * priority and shortens the SLA.
     */
    public static ModerationItem queueLiveListing(UUID id, ProductSubmission product, ListingReport report,
                                                  TriageHint triage, Instant now, SlaPolicy sla) {
        Objects.requireNonNull(report, "report");
        ModerationPriority priority = (triage != null && triage.highRisk()) || report.highRisk()
                ? ModerationPriority.HIGH : ModerationPriority.NORMAL;
        return new ModerationItem(id, product, ModerationItemType.LIVE_LISTING, report, null,
                ModerationStatus.PENDING, priority, triage == null ? TriageStatus.UNAVAILABLE : TriageStatus.COMPLETED,
                triage, now, sla.moderation(now, priority), null, null, false, null, null, null, null, null, null, 0);
    }

    /**
     * Queues a customer review of a booking with the listing SLA; a high-risk AI hint raises the priority and
     * shortens the SLA.
     */
    public static ModerationItem queueReview(UUID id, ReviewSubmission review, TriageHint triage, Instant now,
                                             SlaPolicy sla) {
        Objects.requireNonNull(review, "review");
        ModerationPriority priority = triage != null && triage.highRisk() ? ModerationPriority.HIGH
                : ModerationPriority.NORMAL;
        return new ModerationItem(id, null, ModerationItemType.SERVICE_REVIEW, null, review,
                ModerationStatus.PENDING, priority, triage == null ? TriageStatus.UNAVAILABLE : TriageStatus.COMPLETED,
                triage, now, sla.moderation(now, priority), null, null, false, null, null, null, null, null, null, 0);
    }

    /**
     * Another report or flag for this open live-listing item: counted, and a high-risk flag raises the priority
     * (the SLA is recomputed from the enqueue time unless already escalated).
     */
    public ModerationItem reportedAgain(ListingReport another, SlaPolicy policy) {
        requireOpen();
        ListingReport counted = report == null ? another : report.another();
        ModerationPriority p = another.highRisk() ? ModerationPriority.HIGH : priority;
        Sla timer = escalated || p == priority ? sla : policy.moderation(enqueuedAt, p);
        return new ModerationItem(id, product, type, counted, review, status, p, triageStatus, triage, enqueuedAt,
                timer, assignee, assignedAt, escalated, escalationReason, escalatedAt, decidedBy, decidedAt,
                reasonCode, decisionNote, version);
    }

    public boolean liveListing() {
        return type == ModerationItemType.LIVE_LISTING;
    }

    public boolean serviceReview() {
        return type == ModerationItemType.SERVICE_REVIEW;
    }

    /** What the item is about: the product id of a listing, the booking (= review) id of a review. */
    public UUID subjectId() {
        return serviceReview() ? review.bookingId() : product.productId();
    }

    /** The organisation the decision affects: the listing's seller or the reviewed provider. */
    public String orgId() {
        return serviceReview() ? review.orgId() : product.sellerOrgId();
    }

    /** The listing title, or a one-line summary of the review. */
    public String title() {
        return serviceReview() ? review.summary() : product.title();
    }

    /** Attaches a (re-run) triage hint. The timer is recomputed from the enqueue time unless already escalated. */
    public ModerationItem withTriage(TriageHint hint, SlaPolicy policy) {
        requireOpen();
        ModerationPriority p = hint.highRisk() ? ModerationPriority.HIGH : priority;
        Sla timer = escalated ? sla : policy.moderation(enqueuedAt, p);
        return new ModerationItem(id, product, type, report, review, status, p, TriageStatus.COMPLETED, hint,
                enqueuedAt, timer, assignee, assignedAt, escalated, escalationReason, escalatedAt, decidedBy,
                decidedAt, reasonCode, decisionNote, version);
    }

    public ModerationItem assign(String reviewer, Instant now) {
        requireOpen();
        String who = Texts.required(reviewer, "assignee", "Assignee", 120);
        return new ModerationItem(id, product, type, report, review, status, priority, triageStatus, triage,
                enqueuedAt, sla, who, now, escalated, escalationReason, escalatedAt, decidedBy, decidedAt, reasonCode,
                decisionNote, version);
    }

    /** Sends the item to senior review with a fresh (escalation) timer; the assignee is cleared. */
    public ModerationItem escalate(String reason, Instant now, SlaPolicy policy) {
        if (status != ModerationStatus.PENDING) {
            throw new IllegalStateException("Only pending items can be escalated; this one is " + status);
        }
        String why = Texts.required(reason, "reason", "Escalation reason", 1000);
        return new ModerationItem(id, product, type, report, review, ModerationStatus.ESCALATED, priority, triageStatus,
                triage, enqueuedAt, policy.moderationEscalated(now), null, null, true, why, now, decidedBy, decidedAt,
                reasonCode, decisionNote, version);
    }

    public ModerationItem approve(String by, String note, Instant now) {
        requireOpen();
        return decided(ModerationStatus.APPROVED, by, null, Texts.optional(note, "note", "Note", 1000), now);
    }

    public ModerationItem reject(String by, ModerationReasonCode code, String note, Instant now) {
        requireOpen();
        if (code == null) {
            throw new TrustRuleViolation("trustops.reason-code-required", "Pick a reason code to remove the item",
                    Map.of("reasonCode", "Required"));
        }
        if (!code.appliesTo(type)) {
            throw new TrustRuleViolation("trustops.reason-code-not-applicable", code.name() + " cannot remove "
                    + (serviceReview() ? "a review" : "a listing"),
                    Map.of("reasonCode", serviceReview() ? "Use a review reason code" : "Use a listing reason code"));
        }
        String n = code == ModerationReasonCode.OTHER ? Texts.required(note, "note", "Note", 1000)
                : Texts.optional(note, "note", "Note", 1000);
        return decided(ModerationStatus.REJECTED, by, code, n, now);
    }

    /**
     * The owning service moved the subject on (catalog approved, rejected, took down or withdrew the product;
     * bookings approved or rejected the review); the queue item closes.
     */
    public ModerationItem closeUpstream(String upstreamStatus, Instant now) {
        requireOpen();
        String service = serviceReview() ? "bookings" : "catalog";
        return decided(ModerationStatus.CLOSED, service, null, "Decided in " + service + ": " + upstreamStatus, now);
    }

    /** The note sent to catalog with a rejection: reason label plus the reviewer's note. */
    public String catalogNote() {
        String base = reasonCode == null ? "" : "[" + reasonCode.name() + "] " + reasonCode.label();
        return decisionNote == null ? base : base.isEmpty() ? decisionNote : base + ": " + decisionNote;
    }

    public boolean open() {
        return status.open();
    }

    private ModerationItem decided(ModerationStatus next, String by, ModerationReasonCode code, String note,
                                   Instant now) {
        return new ModerationItem(id, product, type, report, review, next, priority, triageStatus, triage,
                enqueuedAt, sla, assignee, assignedAt, escalated, escalationReason, escalatedAt, by, now, code, note,
                version);
    }

    private void requireOpen() {
        if (!open()) {
            throw new IllegalStateException((serviceReview() ? "Review " : "Listing ") + subjectId() + " is already "
                    + status);
        }
    }
}
