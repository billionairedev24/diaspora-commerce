package com.afrotransact.seller.domain;

/**
 * Timeline actions. Step 1 (reviewer): APPROVE, FLAG, REQUEST_INFO. Step 2 (final approver): FINAL_APPROVE, SEND_BACK, REJECT,
 * and REQUEST_INFO too, so a flagged application can be returned to the applicant to fix.
 */
public enum ReviewAction {
    STARTED,
    SUBMITTED,
    RESUBMITTED,
    APPROVE,
    FLAG,
    REQUEST_INFO,
    FINAL_APPROVE,
    SEND_BACK,
    REJECT;

    public boolean isReviewerAction() {
        return this == APPROVE || this == FLAG || this == REQUEST_INFO;
    }

    public boolean isFinalAction() {
        return this == FINAL_APPROVE || this == SEND_BACK || this == REJECT || this == REQUEST_INFO;
    }
}
