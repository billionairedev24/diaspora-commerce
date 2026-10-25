package com.afrotransact.deliveryruns.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Where an order's pre-order package is on its run (S264), derived from orders-service shipment events: the driver
 * collects it from the seller ({@code picked_up}), drives ({@code out_for_delivery}), delivers it, or fails and tries
 * again. {@code DELIVERED} and {@code RETURNED} close the order's part of the run.
 */
public enum RunOrderStage {
    /** Not collected from the seller yet. */
    PENDING(0),
    /** The driver collected it ({@code picked_up}). */
    COLLECTED(1),
    /** On the van, on the way to the door or the pickup point. */
    OUT_FOR_DELIVERY(2),
    /** A delivery attempt failed; it can still be attempted again (back to {@code OUT_FOR_DELIVERY}) or delivered. */
    FAILED(3),
    /** Handed over; the seller payout hold starts from this time (orders-service / payments). */
    DELIVERED(4),
    /** Came back to the depot after failed attempts; nothing more to deliver. */
    RETURNED(4);

    private final int rank;

    RunOrderStage(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** Nothing more will happen to this order on the run. */
    public boolean closed() {
        return this == DELIVERED || this == RETURNED;
    }

    /** The seller sweep has reached this order (it was collected, or is already further along). */
    public boolean collected() {
        return this != PENDING;
    }

    /**
     * The stage an orders-service shipment status stands for: {@code picked_up}, {@code out_for_delivery},
     * {@code delivery_failed}, {@code delivered}, {@code returned}; empty for every other status.
     */
    public static Optional<RunOrderStage> ofShipmentStatus(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        return switch (wire.trim().toLowerCase(Locale.ROOT)) {
            case "picked_up", "in_transit" -> Optional.of(COLLECTED);
            case "out_for_delivery" -> Optional.of(OUT_FOR_DELIVERY);
            case "delivery_failed" -> Optional.of(FAILED);
            case "delivered" -> Optional.of(DELIVERED);
            case "returned" -> Optional.of(RETURNED);
            default -> Optional.empty();
        };
    }

    /**
     * Whether an event for {@code next} moves an order that is at this stage: forward only, except that a failed
     * attempt goes back on the road. A closed order never moves.
     */
    public boolean accepts(RunOrderStage next) {
        if (closed() || next == this) {
            return false;
        }
        if (this == FAILED) {
            return next == OUT_FOR_DELIVERY || next.closed();
        }
        return next.rank > rank;
    }
}
