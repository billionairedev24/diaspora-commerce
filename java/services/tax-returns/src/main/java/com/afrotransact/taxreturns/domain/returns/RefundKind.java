package com.afrotransact.taxreturns.domain.returns;

/** Why money goes back. */
public enum RefundKind {
    /** Returned (or kept low-value / reported) units of an RMA. */
    RETURN,
    /** A staff or seller partial refund of an amount, without units. */
    ADJUSTMENT
}
