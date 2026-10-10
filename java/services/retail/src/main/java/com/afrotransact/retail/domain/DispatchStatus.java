package com.afrotransact.retail.domain;

/** Delivery state of one {@link PoDispatch}. */
public enum DispatchStatus {
    /** Waiting for the dispatch job (first try or a retry after a transient failure). */
    PENDING,
    /** Delivered (email requested from notifications, or 850 accepted by the transport). */
    DONE,
    /** Gave up: permanent problem or attempts exhausted. A buyer can retry it. */
    FAILED
}
