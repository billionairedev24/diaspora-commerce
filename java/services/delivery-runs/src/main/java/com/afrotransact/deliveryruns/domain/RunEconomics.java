package com.afrotransact.deliveryruns.domain;

/**
 * Economics of a run (S265): the route, the cost model, break-even orders and P&amp;L at the minimum, at capacity and
 * at a given number of orders (expected before the run, actual after it).
 *
 * @param route           one-way route from the origin to the location
 * @param cost            cost model
 * @param feeMinor        run fee per order
 * @param currency        currency
 * @param breakEvenOrders fewest orders whose fees cover the estimated cost; null when a fee never covers the
 *                        per-order cost
 * @param atMinimum       P&amp;L at the run's minimum orders
 * @param atCapacity      P&amp;L at the order capacity (null when orders are not limited)
 * @param atOrders        P&amp;L at the expected (preview) or actual (run) number of orders
 */
public record RunEconomics(RouteEstimate route, CostModel cost, long feeMinor, String currency,
                           Integer breakEvenOrders, EconomicsSnapshot atMinimum, EconomicsSnapshot atCapacity,
                           EconomicsSnapshot atOrders) {
}
