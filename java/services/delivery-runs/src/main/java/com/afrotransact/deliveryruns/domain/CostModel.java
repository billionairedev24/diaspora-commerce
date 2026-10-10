package com.afrotransact.deliveryruns.domain;

/**
 * Estimated run cost as fixed + per-order parts: {@code cost(n) = fixed + n × perOrder}.
 *
 * @param driveMiles        miles driven (round trip when configured)
 * @param fixedMinutes      driving + collection (+ pickup-point stop) minutes
 * @param perOrderMinutes   minutes added by each order (doorstep stop or handover)
 * @param fixedCostMinor    cost of the fixed part, minor units
 * @param perOrderCostMinor cost of each order, minor units (fractional; rounded only in totals)
 */
public record CostModel(double driveMiles, double fixedMinutes, double perOrderMinutes, double fixedCostMinor,
                        double perOrderCostMinor) {

    /** Total estimated cost of a run with {@code orders} orders, rounded to minor units. */
    public long costFor(int orders) {
        return Math.round(fixedCostMinor + orders * perOrderCostMinor);
    }
}
