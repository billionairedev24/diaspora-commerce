package com.afrotransact.taxreturns.domain.profile;

/** Review state of a variant's classification record. Only APPROVED records are charged. */
public enum RecordStatus {
    DRAFT, MISSING_FACTS, PENDING_REVIEW, APPROVED, REJECTED, STALE
}
