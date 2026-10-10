package com.afrotransact.seller.domain;

/** Lifecycle of an organisation; mirrors its onboarding application until approval. */
public enum OrgStatus {
    DRAFT,
    PENDING_REVIEW,
    FLAGGED,
    AWAITING_FINAL_APPROVAL,
    INFO_REQUESTED,
    APPROVED,
    REJECTED,
    SUSPENDED;

    public static OrgStatus of(ApplicationStatus status) {
        return OrgStatus.valueOf(status.name());
    }
}
