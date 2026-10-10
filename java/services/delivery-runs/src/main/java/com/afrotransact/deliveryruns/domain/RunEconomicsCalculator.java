package com.afrotransact.deliveryruns.domain;

/** Estimates what a run costs and earns from the route and the driver cost model. */
public interface RunEconomicsCalculator {

    CostModel costModel(RouteEstimate route, DropMode dropMode);

    /** Fewest orders (at least 1) whose fees cover the cost, or null when the fee never covers the per-order cost. */
    Integer breakEvenOrders(long feeMinor, CostModel cost);

    EconomicsSnapshot at(int orders, long feeMinor, Long commissionShareMinor, CostModel cost);

    /**
     * Full evaluation.
     *
     * @param orders               expected or actual orders for {@link RunEconomics#atOrders()}
     * @param commissionShareMinor reported commission share for {@code orders} (null when unknown)
     */
    RunEconomics evaluate(RouteEstimate route, DropMode dropMode, long feeMinor, String currency, int minOrders,
                          Integer capacityOrders, int orders, Long commissionShareMinor);
}
