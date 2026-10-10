package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.List;

/**
 * Durable queue of calls to orders-service (status transitions, courier events, tracking), written in the business
 * transaction and retried with backoff until orders accepts or refuses them. Commands of one shipment run in order.
 */
public interface OrdersCommandQueue {

    long enqueue(Command command, Instant now);

    /** True when the shipment has a command still waiting (later commands must queue behind it). */
    boolean hasPending(String shipmentId);

    /** Claims due commands (oldest first, at most one per shipment), skipping rows locked by another worker. */
    List<Command> claimDue(int limit, Instant now);

    void done(long id, Instant now);

    void retry(long id, String error, Instant nextAttempt, Instant now);

    void failed(long id, String error, Instant now);

    /** Recently failed commands (dispatch console). */
    List<Command> failures(int limit);

    /**
     * @param kind       TRANSITION, COURIER or TRACKING
     * @param target     status (transition / courier) or carrier (tracking)
     * @param detail     reason (transition) or tracking number (tracking)
     * @param courier    courier event (courier)
     */
    record Command(long id, String shipmentId, String kind, String idempotencyKey, String target, String detail,
                   CourierEvent courier, int attempts, String lastError) {
    }
}
