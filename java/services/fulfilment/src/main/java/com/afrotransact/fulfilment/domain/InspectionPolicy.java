package com.afrotransact.fulfilment.domain;

/** Validates a pre-trip inspection and decides whether the route may start. */
public interface InspectionPolicy {

    /** Throws {@link FulfilmentRuleViolation} when the report is incomplete or invalid. */
    InspectionOutcome evaluate(InspectionReport report, InspectionContext context);
}
