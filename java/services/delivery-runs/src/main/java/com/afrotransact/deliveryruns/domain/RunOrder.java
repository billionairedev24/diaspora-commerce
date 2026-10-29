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
 * @param stage          where the order's package is on the run (S264); {@code PENDING} until the seller pickup
 * @param stageAt        when the package reached {@code stage} (the event's time)
 * @param deliveredAt    when it was handed over (starts the seller payout hold in payments)
 * @param money          what the order paid in delivery fee and in all, how it is delivered, the platform's
 *                       commission and what was refunded (S265); {@link RunOrderMoney#NONE} until reported
 */
public record RunOrder(UUID id, UUID runId, String orderId, String customerId, int packages, RunOrderStatus status,
                       String handoverCode, UUID requestedRunId, Instant reservedAt, Instant updatedAt, long version,
                       RunOrderStage stage, Instant stageAt, Instant deliveredAt, RunOrderMoney money) {

    /** A slot without reported amounts. */
    public RunOrder(UUID id, UUID runId, String orderId, String customerId, int packages, RunOrderStatus status,
                    String handoverCode, UUID requestedRunId, Instant reservedAt, Instant updatedAt, long version,
                    RunOrderStage stage, Instant stageAt, Instant deliveredAt) {
        this(id, runId, orderId, customerId, packages, status, handoverCode, requestedRunId, reservedAt, updatedAt,
                version, stage, stageAt, deliveredAt, RunOrderMoney.NONE);
    }

    /** A slot that has not started its run yet (no delivery progress). */
    public RunOrder(UUID id, UUID runId, String orderId, String customerId, int packages, RunOrderStatus status,
                    String handoverCode, UUID requestedRunId, Instant reservedAt, Instant updatedAt, long version) {
        this(id, runId, orderId, customerId, packages, status, handoverCode, requestedRunId, reservedAt, updatedAt,
                version, RunOrderStage.PENDING, null, null);
    }

    public RunOrder {
        if (orderId == null || orderId.isBlank() || customerId == null || customerId.isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-order", "Order id and customer id are required");
        }
        if (packages < 1 || packages > 50) {
            throw new RuleViolation("delivery-runs.invalid-packages", "An order has between 1 and 50 packages");
        }
        stage = stage == null ? RunOrderStage.PENDING : stage;
        money = money == null ? RunOrderMoney.NONE : money;
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
                reservedAt, now, version, stage, stageAt, deliveredAt, money);
    }

    /**
     * The package moved on its run (an orders-service shipment event at {@code at}). Forward only; a delivered or
     * returned order and an event for the stage it is at change nothing (the same slot comes back), so replays and
     * out-of-order events are harmless. Only confirmed slots take part in the run.
     */
    public RunOrder advance(RunOrderStage next, Instant at, Instant now) {
        if (status != RunOrderStatus.CONFIRMED || !stage.accepts(next)) {
            return this;
        }
        Instant when = at == null ? now : at;
        return new RunOrder(id, runId, orderId, customerId, packages, status, handoverCode, requestedRunId,
                reservedAt, now, version, next, when, next == RunOrderStage.DELIVERED ? when : deliveredAt, money);
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
                now, version, stage, stageAt, deliveredAt, money);
    }

    /** The same slot with the amounts reported for it (checkout's charge, payments' commission and refunds). */
    public RunOrder withMoney(RunOrderMoney reported, Instant now) {
        return new RunOrder(id, runId, orderId, customerId, packages, status, handoverCode, requestedRunId, reservedAt,
                now, version, stage, stageAt, deliveredAt, reported);
    }

    private void requireActive() {
        if (!status.active()) {
            throw new InvalidTransitionException(status.name(), "changed");
        }
    }
}
