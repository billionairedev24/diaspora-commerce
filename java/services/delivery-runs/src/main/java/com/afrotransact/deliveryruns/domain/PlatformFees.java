package com.afrotransact.deliveryruns.domain;

/**
 * The contribution lens of a run (S265): what the platform keeps on the goods of the run's orders (seller commission
 * and fees), shown as its own line "Platform fees on goods" and never mixed into the delivery P&amp;L, so staff can
 * weigh delivery fees and minimums against it.
 *
 * <p>Where it comes from, in order: the commission payments reported on each order ({@code payments.captured});
 * for orders payments has not reported yet, the staff-configured platform fee per order
 * ({@code economics_settings}, audited); a figure typed into the request (MANUAL) replaces both.
 *
 * @param source                  where the figure comes from
 * @param commissionMinor         platform fees on the goods of the run's orders (null when {@link Source#NONE})
 * @param orders                  orders it covers
 * @param ordersReported          of them, orders payments reported the commission of
 * @param perOrderEstimateMinor   the configured per-order estimate used for the others (0 = not configured)
 */
public record PlatformFees(Source source, Long commissionMinor, int orders, int ordersReported,
                           long perOrderEstimateMinor) {

    /** Where the figure comes from. */
    public enum Source {
        /** Every order's commission was reported by payments. */
        PAYMENTS,
        /** Some orders' commission was reported, the rest is the configured estimate. */
        MIXED,
        /** No order's commission is reported: orders x the configured per-order estimate. */
        ESTIMATE,
        /** Typed in by staff for this view. */
        MANUAL,
        /** Nothing reported and no estimate configured. */
        NONE
    }

    /** Nothing known. */
    public static PlatformFees none(int orders, long perOrderEstimateMinor) {
        return new PlatformFees(Source.NONE, null, orders, 0, perOrderEstimateMinor);
    }

    /** Typed in by staff. */
    public static PlatformFees manual(long commissionMinor, int orders) {
        return new PlatformFees(Source.MANUAL, commissionMinor, orders, 0, 0);
    }

    /**
     * What the platform keeps on the goods of {@code orders} orders.
     *
     * @param reportedMinor    commission payments reported
     * @param reportedOrders   orders it covers
     * @param orders           orders in the run
     * @param perOrderEstimate configured estimate for an order payments has not reported (0 = none)
     */
    public static PlatformFees of(long reportedMinor, int reportedOrders, int orders, long perOrderEstimate) {
        int unreported = Math.max(0, orders - reportedOrders);
        if (reportedOrders == 0 && perOrderEstimate == 0) {
            return none(orders, perOrderEstimate);
        }
        if (reportedOrders == 0) {
            return new PlatformFees(Source.ESTIMATE, perOrderEstimate * orders, orders, 0, perOrderEstimate);
        }
        if (unreported == 0) {
            return new PlatformFees(Source.PAYMENTS, reportedMinor, orders, reportedOrders, perOrderEstimate);
        }
        return new PlatformFees(Source.MIXED, reportedMinor + perOrderEstimate * unreported, orders, reportedOrders,
                perOrderEstimate);
    }
}
