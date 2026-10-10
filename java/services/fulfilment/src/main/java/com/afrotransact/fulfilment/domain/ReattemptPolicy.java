package com.afrotransact.fulfilment.domain;

import java.util.List;

/** Validates a drop-off problem report and decides retry / fail / continue. */
public interface ReattemptPolicy {

    /**
     * @param problem         the report
     * @param contacts        contact steps logged for the stop's current attempt
     * @param reattemptsSoFar how many times the stop was already set to retry
     */
    ProblemDecision decide(DeliveryProblem problem, List<ContactAttempt> contacts, int reattemptsSoFar);
}
