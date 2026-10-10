package com.afrotransact.fulfilment.domain;

/** Seller pickup rules: problem flows and pickup confirmation. */
public interface PickupPolicy {

    /** Validates a pickup problem and returns what happens to the stop. */
    StopOutcome decide(PickupProblem problem);

    /**
     * Pickup confirmation needs the name of the seller staff who handed over and every expected label scanned, or a
     * reason for the missing ones (or an earlier partial-pickup problem).
     */
    void checkConfirmation(String handedOverBy, int expected, int scanned, String missingReason,
                           boolean partialAccepted);
}
