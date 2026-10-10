package com.afrotransact.deliveryruns.domain;

/**
 * Run P&amp;L at a number of orders.
 *
 * @param orders                  orders
 * @param feeRevenueMinor         delivery fees collected (orders × fee)
 * @param commissionShareMinor    commission share attributed to the run, when reported (payments/analytics);
 *                                not computed by this service
 * @param revenueMinor            fees + reported commission share
 * @param estimatedCostMinor      estimated driver and vehicle cost
 * @param contributionMarginMinor revenue − estimated cost
 */
public record EconomicsSnapshot(int orders, long feeRevenueMinor, Long commissionShareMinor, long revenueMinor,
                                long estimatedCostMinor, long contributionMarginMinor) {
}
