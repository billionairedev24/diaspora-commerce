package com.afrotransact.deliveryruns.domain;

/** Where the refund of a cancelled pre-order stands (reported by orders-service). */
public enum RefundStatus {

    /** The order was cancelled; orders-service is refunding or releasing the payment. */
    PENDING,

    /** The captured payment was refunded in full, delivery fee included. */
    REFUNDED,

    /** Nothing had been captured: the authorisation was released, the customer is not charged. */
    VOIDED,

    /** The refund failed or the order can no longer be cancelled; staff must act. */
    FAILED;

    /** The money is back with (or was never taken from) the customer. */
    public boolean settled() {
        return this == REFUNDED || this == VOIDED;
    }
}
