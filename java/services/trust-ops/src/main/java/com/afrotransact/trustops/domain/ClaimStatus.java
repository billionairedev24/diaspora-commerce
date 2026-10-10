package com.afrotransact.trustops.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Claim state machine.
 *
 * <pre>
 * AWAITING_SELLER ──seller responds / window lapses──▶ UNDER_REVIEW ──decide──▶ RESOLVED_REFUND | RESOLVED_DENIED
 *        │                                                  │
 *        └──────────────escalate──────────▶ ESCALATED ◀─────┘ (manual, or decision SLA breached) ──decide──▶ …
 * any open state ──customer withdraws──▶ WITHDRAWN
 * </pre>
 */
public enum ClaimStatus {
    AWAITING_SELLER,
    UNDER_REVIEW,
    ESCALATED,
    RESOLVED_REFUND,
    RESOLVED_DENIED,
    WITHDRAWN;

    public boolean open() {
        return this == AWAITING_SELLER || this == UNDER_REVIEW || this == ESCALATED;
    }

    public boolean decidable() {
        return this == UNDER_REVIEW || this == ESCALATED;
    }

    public Set<ClaimStatus> next() {
        return switch (this) {
            case AWAITING_SELLER -> EnumSet.of(UNDER_REVIEW, ESCALATED, WITHDRAWN);
            case UNDER_REVIEW -> EnumSet.of(ESCALATED, RESOLVED_REFUND, RESOLVED_DENIED, WITHDRAWN);
            case ESCALATED -> EnumSet.of(RESOLVED_REFUND, RESOLVED_DENIED, WITHDRAWN);
            case RESOLVED_REFUND, RESOLVED_DENIED, WITHDRAWN -> EnumSet.noneOf(ClaimStatus.class);
        };
    }

    public boolean canMoveTo(ClaimStatus target) {
        return next().contains(target);
    }
}
