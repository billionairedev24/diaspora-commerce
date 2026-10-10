package com.afrotransact.taxreturns.domain.returns;

/** Inspection result of a returned line. */
public enum InspectionOutcome {
    /** Matches the return reason: refund. */
    PASS,
    /** Not as claimed (used, wrong item sent back, missing): no refund for the line. */
    FAIL
}
