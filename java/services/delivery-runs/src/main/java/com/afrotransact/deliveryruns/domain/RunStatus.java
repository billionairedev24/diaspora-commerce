package com.afrotransact.deliveryruns.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Run lifecycle: {@code DRAFT → ANNOUNCED → OPEN → CUT_OFF → CONFIRMED | EXTENDED | CANCELLED → COLLECTING →
 * OUT_FOR_DELIVERY → COMPLETED}. {@code EXTENDED} takes orders again until its new cutoff and is cut off once more.
 */
public enum RunStatus {
    DRAFT,
    ANNOUNCED,
    OPEN,
    EXTENDED,
    CUT_OFF,
    CONFIRMED,
    COLLECTING,
    OUT_FOR_DELIVERY,
    COMPLETED,
    CANCELLED;

    private static final Map<RunStatus, Set<RunStatus>> NEXT = Map.of(
            DRAFT, EnumSet.of(ANNOUNCED, OPEN, CANCELLED),
            ANNOUNCED, EnumSet.of(OPEN, CANCELLED),
            OPEN, EnumSet.of(CUT_OFF, EXTENDED, CANCELLED),
            EXTENDED, EnumSet.of(CUT_OFF, EXTENDED, CANCELLED),
            CUT_OFF, EnumSet.of(CONFIRMED, EXTENDED, CANCELLED),
            CONFIRMED, EnumSet.of(COLLECTING, CANCELLED),
            COLLECTING, EnumSet.of(OUT_FOR_DELIVERY),
            OUT_FOR_DELIVERY, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(RunStatus.class),
            CANCELLED, EnumSet.noneOf(RunStatus.class));

    public boolean canMoveTo(RunStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** Statuses in which shoppers can preorder (until the cutoff instant). */
    public boolean takesOrders() {
        return this == OPEN || this == EXTENDED;
    }

    /** Statuses shown on public pages and advertorials. */
    public boolean isPublic() {
        return this != DRAFT;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
