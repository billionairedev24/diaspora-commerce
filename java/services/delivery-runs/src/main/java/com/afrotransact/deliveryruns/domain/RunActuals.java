package com.afrotransact.deliveryruns.domain;

import java.util.List;

/**
 * A run's delivery P&amp;L built from its real orders (S265), not from orders x one fee: the orders the run carried,
 * delivered, lost and refunded, the delivery fees actually charged per way of delivery (door, pickup and carrier
 * differ), the money handed back, the cost, the contribution margin and the break-even at the fee the orders paid.
 * Platform fees on the goods are not here: they are a separate lens ({@link PlatformFees}).
 *
 * @param ordersCarried          orders that held a place on the run
 * @param ordersDelivered        of them, delivered (BEFORE the run: the orders it will deliver)
 * @param ordersFailed           of them, not delivered (stage FAILED or RETURNED)
 * @param ordersCancelled        slots customers gave back after reserving (they earn and cost nothing)
 * @param ordersRefunded         delivered orders money was handed back on
 * @param feeMix                 delivery fees charged on the delivered orders, per way of delivery
 * @param deliveryFeesMinor      delivery fees charged on the delivered orders
 * @param refundedFeesMinor      of them, handed back (from the refunds payments issued)
 * @param netDeliveryFeesMinor   fees kept: charged minus handed back
 * @param feesEstimated          some order had no reported fee, the run's fee stands in for it (labelled estimate)
 * @param customersPaidMinor     what the delivered orders' customers paid in all, delivery fees included (reported
 *                               totals; see {@code totalsComplete})
 * @param totalsComplete         every delivered order's total was reported
 * @param refundsMinor           all money handed back on the delivered orders (goods and fees)
 * @param averageFeeMinor        average delivery fee charged per delivered order (null without orders)
 * @param costBasis              how {@code estimatedCostMinor} was worked out
 * @param actualMinutes          minutes the driver was out, from the run's collecting / out-for-delivery and completed
 *                               times (null when not captured or not plausible)
 * @param estimatedCostMinor     driver and vehicle cost
 * @param contributionMarginMinor net delivery fees minus the cost
 * @param breakEvenOrders        fewest orders whose fees cover the cost at the average fee actually charged (null
 *                               without orders or when that fee never covers the per-order cost)
 * @param breakEvenCovered       the orders delivered reach it (null without a break-even)
 */
public record RunActuals(int ordersCarried, int ordersDelivered, int ordersFailed, int ordersCancelled,
                         int ordersRefunded, List<RunOrderTally.FeeLine> feeMix, long deliveryFeesMinor,
                         long refundedFeesMinor, long netDeliveryFeesMinor, boolean feesEstimated,
                         long customersPaidMinor, boolean totalsComplete, long refundsMinor, Double averageFeeMinor,
                         CostBasis costBasis, Double actualMinutes, long estimatedCostMinor,
                         long contributionMarginMinor, Integer breakEvenOrders, Boolean breakEvenCovered) {

    /** How the cost was worked out. */
    public enum CostBasis {
        /** Route time and miles from geo-service, the cost model's stop times. */
        ESTIMATE,
        /** The minutes the driver was actually out (captured from the run's times), miles from geo-service. */
        ACTUAL_TIME
    }
}
