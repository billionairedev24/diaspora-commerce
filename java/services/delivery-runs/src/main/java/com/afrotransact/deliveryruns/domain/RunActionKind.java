package com.afrotransact.deliveryruns.domain;

/** What was decided about a run (the entries of its action log). */
public enum RunActionKind {
    /** The cutoff moved later (by staff, or by the run's cutoff policy). */
    EXTEND,
    /** Confirmed with the minimum met (the cutoff job, or staff). */
    CONFIRM,
    /** Confirmed by staff although the minimum was not met: always has a reason. */
    CONFIRM_OVERRIDE,
    /** Cancelled: every order on the run is refunded and its stock released. Always has a reason. */
    CANCEL
}
