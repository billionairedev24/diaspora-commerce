package com.afrotransact.deliveryruns.domain;

/** Entries of a window's decision log. */
public enum DecisionKind {

    /** The system found the window below its minimum at the close date and is waiting for staff. */
    AWAITING,

    /** Staff chose to go ahead below the minimum. */
    PROCEED,

    /** Staff cancelled the window (refunding every order). */
    CANCEL,

    /** Staff closed the window before its close date because the minimum was met. */
    EARLY_CLOSE
}
