package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a run's action log: who decided what, when and why.
 *
 * @param id         entry id
 * @param runId      the run
 * @param kind       what was decided
 * @param reason     why (required for a cancellation and a minimum override; optional for an extension)
 * @param actor      staff subject, or {@code system} for the cutoff job
 * @param fromStatus the run's status before
 * @param toStatus   the run's status after
 * @param orders     orders on the run when it was decided
 * @param minimum    the run's minimum orders at that time
 * @param fromCutoff EXTEND: the cutoff before
 * @param toCutoff   EXTEND: the cutoff after
 * @param at         when
 */
public record RunAction(UUID id, UUID runId, RunActionKind kind, String reason, String actor, RunStatus fromStatus,
                        RunStatus toStatus, int orders, int minimum, Instant fromCutoff, Instant toCutoff,
                        Instant at) {

    /** Actor of decisions taken by the cutoff job. */
    public static final String SYSTEM = "system";

    /** Longest reason kept. */
    public static final int MAX_REASON = 500;

    public RunAction {
        if (runId == null || kind == null || at == null || fromStatus == null || toStatus == null) {
            throw new IllegalArgumentException("run, kind, statuses and time are required");
        }
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw new RuleViolation("delivery-runs.reason-too-long", "The reason is at most " + MAX_REASON
                    + " characters");
        }
        if (reason == null && (kind == RunActionKind.CANCEL || kind == RunActionKind.CONFIRM_OVERRIDE)) {
            throw new RuleViolation("delivery-runs.reason-required", "A reason is required");
        }
        actor = actor == null || actor.isBlank() ? SYSTEM : actor;
    }

    /**
     * An entry for a run that has just left {@code from} and is now {@code after}.
     *
     * @param fromCutoff the cutoff before an extension (null for other actions)
     */
    public static RunAction of(RunActionKind kind, String reason, String actor, RunStatus from, Run after,
                               Instant fromCutoff, Instant at) {
        return new RunAction(UUID.randomUUID(), after.id(), kind, reason, actor, from, after.status(),
                after.reservedOrders(), after.terms().minOrders(), fromCutoff,
                fromCutoff == null ? null : after.terms().cutoffAt(), at);
    }
}
