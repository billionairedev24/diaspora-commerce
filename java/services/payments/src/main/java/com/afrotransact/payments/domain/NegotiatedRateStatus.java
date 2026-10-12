package com.afrotransact.payments.domain;

/** Lifecycle of a negotiated rate, derived from its dates. */
public enum NegotiatedRateStatus {
    /** Starts later. */
    SCHEDULED,
    /** In force now. */
    ACTIVE,
    /** Ended early by staff (with a reason). */
    ENDED,
    /** Reached its end date. */
    EXPIRED
}
