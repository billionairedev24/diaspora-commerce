package com.afrotransact.deliveryruns.domain;

/**
 * A run's P&amp;L before and after it (S265), or what a cancelled run refunded instead of earning.
 *
 * @param economics            route, cost model, break-even (at the run's fee) and P&amp;L; {@code atOrders} is the
 *                             run's delivery P&amp;L at its real orders; for a cancelled run every figure at orders is zero
 * @param status               the run's status
 * @param phase                {@code BEFORE} the run was driven (the orders reserved so far), {@code AFTER} once it
 *                             completed (the orders it delivered), {@code CANCELLED} (nothing earned, nothing spent)
 * @param cancelReason         why a cancelled run was cancelled
 * @param ordersRefunded       orders refunded: a cancelled run's orders (each refunded in full); otherwise the
 *                             delivered orders money was handed back on (see {@link RunOrderTally})
 * @param refundedFeesMinor    delivery fees handed back, from the fees the orders were actually charged
 * @param refundedFeesEstimated some of those orders had no reported fee, the run's fee stands in for it
 * @param actuals              the delivery P&amp;L from the real orders (null for a cancelled run)
 * @param platformFees         the contribution lens: platform fees on the goods, never part of the delivery P&amp;L
 *                             (null for a cancelled run: nothing was sold)
 */
public record RunPnl(RunEconomics economics, RunStatus status, Phase phase, String cancelReason, int ordersRefunded,
                     long refundedFeesMinor, boolean refundedFeesEstimated, RunActuals actuals,
                     PlatformFees platformFees) {

    /** Where the run stands for its P&amp;L. */
    public enum Phase {
        BEFORE,
        AFTER,
        CANCELLED
    }

    /** The phase of a run in {@code status}. */
    public static Phase phaseOf(RunStatus status) {
        return switch (status) {
            case CANCELLED -> Phase.CANCELLED;
            case COMPLETED -> Phase.AFTER;
            default -> Phase.BEFORE;
        };
    }
}
