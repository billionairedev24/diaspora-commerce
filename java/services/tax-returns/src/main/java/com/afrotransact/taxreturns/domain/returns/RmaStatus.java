package com.afrotransact.taxreturns.domain.returns;

import java.util.EnumSet;
import java.util.Set;

/**
 * RMA lifecycle: {@code requested → approved | auto_approved → label_issued → in_transit → received → inspected →
 * refunded | rejected}. Refunds without return go {@code approved → refunded}. Customers cancel before drop-off.
 */
public enum RmaStatus {
    REQUESTED,
    APPROVED,
    AUTO_APPROVED,
    LABEL_ISSUED,
    IN_TRANSIT,
    RECEIVED,
    INSPECTED,
    REFUNDED,
    REJECTED,
    CANCELLED;

    /** Statuses the RMA may move to next. */
    public Set<RmaStatus> next() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(APPROVED, AUTO_APPROVED, REJECTED, CANCELLED);
            case APPROVED, AUTO_APPROVED -> EnumSet.of(LABEL_ISSUED, REFUNDED, CANCELLED);
            case LABEL_ISSUED -> EnumSet.of(IN_TRANSIT, RECEIVED, CANCELLED);
            case IN_TRANSIT -> EnumSet.of(RECEIVED);
            case RECEIVED -> EnumSet.of(INSPECTED);
            case INSPECTED -> EnumSet.of(REFUNDED, REJECTED);
            case REFUNDED, REJECTED, CANCELLED -> EnumSet.noneOf(RmaStatus.class);
        };
    }

    public boolean canMoveTo(RmaStatus to) {
        return next().contains(to);
    }

    public boolean approved() {
        return this == APPROVED || this == AUTO_APPROVED;
    }

    /** Still open (counts against the returnable quantity). */
    public boolean open() {
        return this != REJECTED && this != CANCELLED;
    }

    /** The customer may still cancel (nothing handed over yet). */
    public boolean cancellable() {
        return canMoveTo(CANCELLED);
    }
}
