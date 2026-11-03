package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a window's decision log: who decided what, when and why (ADR 0005 §4).
 *
 * @param id       entry id
 * @param windowId the window
 * @param kind     what was decided
 * @param reason   why (required for staff decisions; the system's own note for AWAITING)
 * @param actor    staff subject, or {@code system} for the scheduler
 * @param orders   orders that counted when it was decided
 * @param minimum  the window's minimum at that time
 * @param at       when
 */
public record WindowDecision(UUID id, UUID windowId, DecisionKind kind, String reason, String actor, int orders,
                             int minimum, Instant at) {

    /** Actor of decisions taken by the scheduler. */
    public static final String SYSTEM = "system";

    /** Longest reason kept. */
    public static final int MAX_REASON = 500;

    public WindowDecision {
        if (windowId == null || kind == null || at == null) {
            throw new IllegalArgumentException("window, kind and time are required");
        }
        reason = reason == null ? "" : reason.trim();
        if (reason.length() > MAX_REASON) {
            throw new RuleViolation("delivery-runs.reason-too-long", "The reason is at most " + MAX_REASON
                    + " characters");
        }
        actor = actor == null || actor.isBlank() ? SYSTEM : actor;
    }

    /** A staff decision: the reason is required. */
    public static WindowDecision byStaff(UUID windowId, DecisionKind kind, String reason, String actor, int orders,
                                         int minimum, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new RuleViolation("delivery-runs.reason-required", "A reason is required");
        }
        return new WindowDecision(UUID.randomUUID(), windowId, kind, reason, actor, orders, minimum, at);
    }

    /** The scheduler's note that a window is waiting for staff. */
    public static WindowDecision awaiting(UUID windowId, int orders, int minimum, Instant at) {
        return new WindowDecision(UUID.randomUUID(), windowId, DecisionKind.AWAITING,
                "Closed with " + orders + " of " + minimum + " orders", SYSTEM, orders, minimum, at);
    }
}
