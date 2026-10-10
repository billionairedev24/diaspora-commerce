package com.afrotransact.seller.domain;

/** Onboarding application states (two-step review: reviewer, then a different final approver). */
public enum ApplicationStatus {
    DRAFT,
    PENDING_REVIEW,
    FLAGGED,
    AWAITING_FINAL_APPROVAL,
    INFO_REQUESTED,
    APPROVED,
    REJECTED;

    /** The applicant may edit the draft only before submission or when info was requested. */
    public boolean editableByApplicant() {
        return this == DRAFT || this == INFO_REQUESTED;
    }

    /** Waiting for the step-2 (final) approver. */
    public boolean awaitingFinalDecision() {
        return this == AWAITING_FINAL_APPROVAL || this == FLAGGED;
    }
}
