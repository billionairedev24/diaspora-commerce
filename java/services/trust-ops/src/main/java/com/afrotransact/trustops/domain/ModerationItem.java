package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A listing in the moderation queue with its SLA timer, assignee, AI triage hint and the human decision.
 * {@code PENDING → ESCALATED → APPROVED | REJECTED}, or {@code CLOSED} when catalog decided it elsewhere.
 */
public record ModerationItem(UUID id, ProductSubmission product, ModerationStatus status, ModerationPriority priority,
                             TriageStatus triageStatus, TriageHint triage, Instant enqueuedAt, Sla sla,
                             String assignee, Instant assignedAt, boolean escalated, String escalationReason,
                             Instant escalatedAt, String decidedBy, Instant decidedAt, ModerationReasonCode reasonCode,
                             String decisionNote, long version) {

    public ModerationItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(product, "product");
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
        return new ModerationItem(id, product, ModerationStatus.PENDING, priority,
                triage == null ? TriageStatus.UNAVAILABLE : TriageStatus.COMPLETED, triage, now,
                sla.moderation(now, priority), null, null, false, null, null, null, null, null, null, 0);
    }

    /** Attaches a (re-run) triage hint. The timer is recomputed from the enqueue time unless already escalated. */
    public ModerationItem withTriage(TriageHint hint, SlaPolicy policy) {
        requireOpen();
        ModerationPriority p = hint.highRisk() ? ModerationPriority.HIGH : priority;
        Sla timer = escalated ? sla : policy.moderation(enqueuedAt, p);
        return new ModerationItem(id, product, status, p, TriageStatus.COMPLETED, hint, enqueuedAt, timer, assignee,
                assignedAt, escalated, escalationReason, escalatedAt, decidedBy, decidedAt, reasonCode, decisionNote,
                version);
    }

    public ModerationItem assign(String reviewer, Instant now) {
        requireOpen();
        String who = Texts.required(reviewer, "assignee", "Assignee", 120);
        return new ModerationItem(id, product, status, priority, triageStatus, triage, enqueuedAt, sla, who, now,
                escalated, escalationReason, escalatedAt, decidedBy, decidedAt, reasonCode, decisionNote, version);
    }

    /** Sends the item to senior review with a fresh (escalation) timer; the assignee is cleared. */
    public ModerationItem escalate(String reason, Instant now, SlaPolicy policy) {
        if (status != ModerationStatus.PENDING) {
            throw new IllegalStateException("Only pending listings can be escalated; this one is " + status);
        }
        String why = Texts.required(reason, "reason", "Escalation reason", 1000);
        return new ModerationItem(id, product, ModerationStatus.ESCALATED, priority, triageStatus, triage, enqueuedAt,
                policy.moderationEscalated(now), null, null, true, why, now, decidedBy, decidedAt, reasonCode,
                decisionNote, version);
    }

    public ModerationItem approve(String by, String note, Instant now) {
        requireOpen();
        return decided(ModerationStatus.APPROVED, by, null, Texts.optional(note, "note", "Note", 1000), now);
    }

    public ModerationItem reject(String by, ModerationReasonCode code, String note, Instant now) {
        requireOpen();
        if (code == null) {
            throw new TrustRuleViolation("trustops.reason-code-required", "Pick a reason code to reject a listing",
                    Map.of("reasonCode", "Required"));
        }
        String n = code == ModerationReasonCode.OTHER ? Texts.required(note, "note", "Note", 1000)
                : Texts.optional(note, "note", "Note", 1000);
        return decided(ModerationStatus.REJECTED, by, code, n, now);
    }

    /** Catalog moved the product on (approved, rejected or withdrawn there); the queue item closes. */
    public ModerationItem closeUpstream(String upstreamStatus, Instant now) {
        requireOpen();
        return decided(ModerationStatus.CLOSED, "catalog", null, "Decided in catalog: " + upstreamStatus, now);
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
        return new ModerationItem(id, product, next, priority, triageStatus, triage, enqueuedAt, sla, assignee,
                assignedAt, escalated, escalationReason, escalatedAt, by, now, code, note, version);
    }

    private void requireOpen() {
        if (!open()) {
            throw new IllegalStateException("Listing " + product.productId() + " is already " + status);
        }
    }
}
