package com.afrotransact.orders.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Side effects of a transition (capture, settlement, refund / release) written in the same transaction as the
 * transition and executed afterwards with retries (a transactional outbox for commands).
 */
public interface FollowUpRepository {

    /** Adds the task unless one with the same key exists. */
    void enqueue(FollowUp task);

    /** Claims up to {@code limit} due tasks by pushing their next attempt {@code lease} into the future. */
    List<FollowUp> claimDue(Instant now, Duration lease, int limit);

    void done(String id, Instant at);

    void retry(String id, int attempts, Instant nextAttemptAt, String error);

    void fail(String id, int attempts, String error);

    /** Kinds of follow-up work. */
    enum Kind {
        /** Capture one shipment's amount and commit its inventory. */
        CAPTURE,
        /** Every live shipment delivered: start the settlement hold. */
        SETTLE,
        /** A shipment was cancelled: refund it, or cancel the payment and release stock when nothing is left. */
        CANCEL,
        /** One shipment of a multi-shipment order delivered: start that shipment's settlement hold. */
        SETTLE_SHIPMENT
    }

    /** One task. */
    record FollowUp(String id, String key, Kind kind, String orderId, String shipmentId, int attempts,
                    Instant nextAttemptAt) {
    }
}
