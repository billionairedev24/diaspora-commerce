package com.afrotransact.orders.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The pre-order window (or delivery run) a PREORDER shipment belongs to (S272, S261), snapshotted at checkout.
 *
 * @param windowId         delivery-runs pre-order window; for a {@link PreorderSource#RUN} the id of the run (the id of
 *                         the offer the package belongs to)
 * @param windowCode       e.g. LBK-7 (may be null when delivery-runs did not send it)
 * @param windowName       e.g. "Lubbock Pre-order #7", or "Bastrop delivery run"
 * @param deliversOn       the delivery date (also the shipment's promisedBy)
 * @param closesAt         end of the window, or the run's cutoff: the customer may cancel for a full refund until then
 *                         (null: unknown, the usual cancellation rules apply)
 * @param fulfilment       door delivery or pickup
 * @param deliveryMissedAt when the scheduler found the shipment undelivered after {@code deliversOn}
 * @param missedChoice     the customer's answer to the missed delivery (null until answered)
 * @param missedAnsweredAt when the customer answered
 * @param source           what it was sold through; {@link PreorderSource#WINDOW} when absent (orders placed before
 *                         runs were offered)
 */
public record PreorderPackage(String windowId, String windowCode, String windowName, LocalDate deliversOn,
                              Instant closesAt, PreorderFulfilment fulfilment, Instant deliveryMissedAt,
                              MissedDeliveryChoice missedChoice, Instant missedAnsweredAt, PreorderSource source) {

    public PreorderPackage {
        if (windowId == null || windowId.isBlank()) {
            throw new IllegalArgumentException("windowId is required");
        }
        Objects.requireNonNull(deliversOn, "deliversOn");
        Objects.requireNonNull(fulfilment, "fulfilment");
        source = source == null ? PreorderSource.WINDOW : source;
    }

    /** A window's package, as stored before runs were offered. */
    public PreorderPackage(String windowId, String windowCode, String windowName, LocalDate deliversOn,
                           Instant closesAt, PreorderFulfilment fulfilment, Instant deliveryMissedAt,
                           MissedDeliveryChoice missedChoice, Instant missedAnsweredAt) {
        this(windowId, windowCode, windowName, deliversOn, closesAt, fulfilment, deliveryMissedAt, missedChoice,
                missedAnsweredAt, PreorderSource.WINDOW);
    }

    /** A window's package as placed at checkout. */
    public static PreorderPackage placed(String windowId, String windowCode, String windowName, LocalDate deliversOn,
                                         Instant closesAt, PreorderFulfilment fulfilment) {
        return placed(windowId, windowCode, windowName, deliversOn, closesAt, fulfilment, PreorderSource.WINDOW);
    }

    /** A package as placed at checkout, sold through {@code source}. */
    public static PreorderPackage placed(String windowId, String windowCode, String windowName, LocalDate deliversOn,
                                         Instant closesAt, PreorderFulfilment fulfilment, PreorderSource source) {
        return new PreorderPackage(windowId, windowCode, windowName, deliversOn, closesAt, fulfilment, null, null,
                null, source);
    }

    /** Sold through a delivery run staff scheduled: the order holds a slot on {@link #runId()}. */
    public boolean run() {
        return source == PreorderSource.RUN;
    }

    /** The delivery run holding the order's slot; null for a window. */
    public String runId() {
        return run() ? windowId : null;
    }

    /** The customer may still cancel for a full refund (the window has not closed, the run's cutoff has not passed). */
    public boolean cancellableAt(Instant now) {
        return closesAt == null || now.isBefore(closesAt);
    }

    public boolean deliveryMissed() {
        return deliveryMissedAt != null;
    }

    /** Marks the delivery date missed (idempotent). */
    public PreorderPackage missed(Instant at) {
        return deliveryMissedAt != null ? this : new PreorderPackage(windowId, windowCode, windowName, deliversOn,
                closesAt, fulfilment, at, missedChoice, missedAnsweredAt, source);
    }

    /** Records the customer's answer; the same answer again is a no-op, a different one after answering refused. */
    public PreorderPackage answered(MissedDeliveryChoice choice, Instant at) {
        Objects.requireNonNull(choice, "choice");
        if (deliveryMissedAt == null) {
            throw new OrdersStateConflict("orders.delivery-not-missed", "This pre-order has not missed its delivery "
                    + "date");
        }
        if (missedChoice == choice) {
            return this;
        }
        if (missedChoice == MissedDeliveryChoice.REFUND) {
            throw new OrdersStateConflict("orders.missed-delivery-answered", "A refund was already chosen");
        }
        return new PreorderPackage(windowId, windowCode, windowName, deliversOn, closesAt, fulfilment,
                deliveryMissedAt, choice, at, source);
    }
}
