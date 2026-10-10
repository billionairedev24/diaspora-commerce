package com.afrotransact.seller.domain;

/** Timeline actions. Step 1 (reviewer): APPROVE, FLAG, REQUEST_INFO. Step 2 (final approver): the rest. */
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
        return this == FINAL_APPROVE || this == SEND_BACK || this == REJECT;
    }
}
