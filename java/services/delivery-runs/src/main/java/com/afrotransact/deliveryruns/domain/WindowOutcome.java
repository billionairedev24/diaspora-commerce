package com.afrotransact.deliveryruns.domain;

/**
 * What became of a pre-order window at (or before) its close, beyond its dates. A window without an outcome simply
 * follows its dates (ADR 0005 §4).
 */
public enum WindowOutcome {

    /** The close date passed below the staff-set minimum: nothing happens until staff cancel or proceed. */
    AWAITING_DECISION,

    /** Staff decided to go ahead below the minimum: the window closes and hands its orders to a run as usual. */
    PROCEEDED,

    /** The window was cancelled: every order on it is refunded in full and its stock released. */
    CANCELLED
}
