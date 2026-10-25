package com.afrotransact.deliveryruns.domain;

/**
 * A run's P&amp;L before and after it (S265), or what a cancelled run refunded instead of earning.
 *
 * @param economics         route, cost model, break-even and P&amp;L; for a cancelled run every figure at orders is zero
 * @param status            the run's status
 * @param phase             {@code BEFORE} the run was driven (estimate at the orders reserved so far), {@code AFTER}
 *                          once it completed, {@code CANCELLED} (nothing earned, nothing spent)
 * @param cancelReason      why a cancelled run was cancelled
 * @param ordersRefunded    orders of a cancelled run, each refunded in full
 * @param refundedFeesMinor delivery fees handed back (orders × fee)
 */
public record RunPnl(RunEconomics economics, RunStatus status, Phase phase, String cancelReason, int ordersRefunded,
                     long refundedFeesMinor) {

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
