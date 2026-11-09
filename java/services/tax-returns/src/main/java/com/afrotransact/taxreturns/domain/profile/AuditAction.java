package com.afrotransact.taxreturns.domain.profile;

/** What happened to a classification record (append-only audit). */
public enum AuditAction {
    FACTS_CHANGED, SUBMITTED, APPROVED, REJECTED, RECLASSIFIED, MARKED_STALE, EVIDENCE_ADDED
}
