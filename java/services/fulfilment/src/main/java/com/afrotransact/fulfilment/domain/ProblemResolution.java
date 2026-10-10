package com.afrotransact.fulfilment.domain;

/** What happens to a drop-off after a problem. */
public enum ProblemResolution {
    /** Try again later on this route (stop shows "Retry later"; must be resolved before the shift ends). */
    REATTEMPT,
    /** Take it to an AfroTransact pickup point; the customer is notified. Scanned back at end of shift. */
    PICKUP_POINT,
    /** Return to the fulfilment centre; scanned back in at end of shift. */
    RETURN_TO_FC,
    /** The customer accepted a damaged package: a damage claim is logged and the delivery continues. */
    ACCEPTED_WITH_DAMAGE,
    /** Package not found on board: reported, dispatch investigates. */
    REPORT_MISSING
}
