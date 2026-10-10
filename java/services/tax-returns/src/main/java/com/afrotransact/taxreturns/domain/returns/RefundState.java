package com.afrotransact.taxreturns.domain.returns;

/** Refund progress of an RMA, separate from the lifecycle (refund at first scan pays before receipt). */
public enum RefundState {
    NONE,
    PENDING,
    ISSUED,
    FAILED
}
