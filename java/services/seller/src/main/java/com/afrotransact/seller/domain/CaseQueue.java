package com.afrotransact.seller.domain;

/** The staff console's queue tabs. */
public enum CaseQueue {
    /** Assigned to me, plus unassigned cases I am eligible to claim. */
    MY,
    AWAITING_REVIEW,
    AWAITING_APPROVAL,
    ESCALATED,
    /** With the applicant after a request for information. */
    RETURNED,
    ALL
}
