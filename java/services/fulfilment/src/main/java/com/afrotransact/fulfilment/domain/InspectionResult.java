package com.afrotransact.fulfilment.domain;

/** Overall inspection outcome. */
public enum InspectionResult {
    /** Every item passed. */
    PASSED,
    /** Only non-critical items failed: the route may start, the defect is reported to dispatch. */
    PASSED_WITH_DEFECTS,
    /** Brakes, tyres or lights failed: the route is blocked and dispatch must assign another vehicle. */
    FAILED_CRITICAL;

    public boolean allowsRouteStart() {
        return this != FAILED_CRITICAL;
    }

    public boolean defect() {
        return this != PASSED;
    }
}
