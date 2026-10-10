package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The link between a run and an order (orders-service owns the order itself).
 *
 * @param id             slot id
 * @param runId          run
 * @param orderId        orders-service order id
 * @param customerId     customer (token subject), for notifications
 * @param packages       packages reserved against the run's package capacity
 * @param status         slot status
 * @param handoverCode   six-digit code for pickup-point runs, issued at confirmation
 * @param requestedRunId the run the order asked for when it was moved to the next run (full or closed)
 * @param reservedAt     when the slot was taken
 * @param updatedAt      last change
 * @param version        optimistic lock
 */
public record RunOrder(UUID id, UUID runId, String orderId, String customerId, int packages, RunOrderStatus status,
                       String handoverCode, UUID requestedRunId, Instant reservedAt, Instant updatedAt, long version) {

    public RunOrder {
        if (orderId == null || orderId.isBlank() || customerId == null || customerId.isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-order", "Order id and customer id are required");
        }
        if (packages < 1 || packages > 50) {
            throw new RuleViolation("delivery-runs.invalid-packages", "An order has between 1 and 50 packages");
        }
    }

    public static RunOrder reserve(UUID id, UUID runId, String orderId, String customerId, int packages,
                                   UUID requestedRunId, Instant now) {
        return new RunOrder(id, runId, orderId, customerId, packages, RunOrderStatus.RESERVED, null,
                requestedRunId != null && !requestedRunId.equals(runId) ? requestedRunId : null, now, now, 0);
    }

    /** Run confirmed; pickup-point runs hand over against {@code code}. */
    public RunOrder confirm(String code, Instant now) {
        requireActive();
        return new RunOrder(id, runId, orderId, customerId, packages, RunOrderStatus.CONFIRMED, code, requestedRunId,
                reservedAt, now, version);
    }

    public RunOrder release(Instant now) {
        return to(RunOrderStatus.RELEASED, now);
    }

    public RunOrder cancel(Instant now) {
        return to(RunOrderStatus.CANCELLED, now);
    }

    public boolean movedToNextRun() {
        return requestedRunId != null;
    }

    private RunOrder to(RunOrderStatus next, Instant now) {
        requireActive();
        return new RunOrder(id, runId, orderId, customerId, packages, next, handoverCode, requestedRunId, reservedAt,
                now, version);
    }

    private void requireActive() {
        if (!status.active()) {
            throw new InvalidTransitionException(status.name(), "changed");
        }
    }
}
