package com.afrotransact.deliveryruns.application;

import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunOrderMoney;

/**
 * orders-service reserves a slot for an order.
 *
 * @param runId        the run the customer chose at checkout
 * @param orderId      order id; reserving the same order again returns its existing slot (idempotent)
 * @param customerId   customer (token subject)
 * @param packages     packages the order adds to the run (default 1)
 * @param allowNextRun when the run is full or past cutoff, take a slot on the location's next open run instead
 *                     (default true); false refuses with 409
 * @param earlyAccessHours S323: the customer's membership lets them order from a run that opens within this many
 *                     hours (null or 0: no early access)
 * @param charge       S265: what the order is charged (delivery fee, total, how it is delivered), kept on the slot so
 *                     the run's P&amp;L is built from it; null when the caller does not say
 */
public record ReserveCommand(UUID runId, String orderId, String customerId, Integer packages, Boolean allowNextRun,
                             Integer earlyAccessHours, RunOrderMoney charge) {

    /** A reservation that does not say what the order is charged. */
    public ReserveCommand(UUID runId, String orderId, String customerId, Integer packages, Boolean allowNextRun,
                          Integer earlyAccessHours) {
        this(runId, orderId, customerId, packages, allowNextRun, earlyAccessHours, null);
    }

    /** A reservation of a shopper without early access. */
    public ReserveCommand(UUID runId, String orderId, String customerId, Integer packages, Boolean allowNextRun) {
        this(runId, orderId, customerId, packages, allowNextRun, null);
    }
}
