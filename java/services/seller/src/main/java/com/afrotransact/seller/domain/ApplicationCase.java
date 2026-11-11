package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Who currently holds an application (the "case"): the stage, the assignee or nobody (the pool), how long it has been
 * at this stage, its escalation and the last time the approver sent it back. One per application; it never changes
 * the application's review state machine, only says whose turn it is.
 *
 * @param stage             null while no staff member holds it (draft, with the applicant, decided)
 * @param stageEnteredAt    when the application reached this stage; the SLA clock
 * @param escalationLevel   0 none, 1 or 2
 * @param escalationTargetNames the people (names) the escalation went to, for display
 * @param returnCount       how many times an approver sent the application back to review
 */
public record ApplicationCase(String applicationId, ReviewStage stage, String assigneeId, String assigneeName,
                              String assignedById, String assignedByName, Instant assignedAt, Instant stageEnteredAt,
                              int escalationLevel, Instant escalatedAt, String escalationReason,
                              List<String> escalationTargetIds, List<String> escalationTargetNames,
                              String returnedById, String returnedByName, Instant returnedAt, String returnNote,
                              int returnCount, Instant updatedAt, long version) {

    public ApplicationCase {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(stageEnteredAt, "stageEnteredAt");
        escalationTargetIds = escalationTargetIds == null ? List.of() : List.copyOf(escalationTargetIds);
        escalationTargetNames = escalationTargetNames == null ? List.of() : List.copyOf(escalationTargetNames);
    }

    /** A case that nobody holds yet. */
    public static ApplicationCase idle(String applicationId, Instant now) {
        return new ApplicationCase(applicationId, null, null, null, null, null, null, now, 0, null, null, List.of(),
                List.of(), null, null, null, null, 0, now, 0);
    }

    public boolean assigned() {
        return assigneeId != null;
    }

    /**
     * The application reached {@code next} (null: left staff hands). The assignee and any escalation end, the wait
     * starts again; the record of a send-back stays until step 1 is done again ({@link #withoutReturn}).
     */
    public ApplicationCase enter(ReviewStage next, Instant now) {
        return new ApplicationCase(applicationId, next, null, null, null, null, null, now, 0, null, null, List.of(),
                List.of(), returnedById, returnedByName, returnedAt, returnNote, returnCount, now, version);
    }

    /** Gives the case to a person; the wait does not restart. */
    public ApplicationCase assign(String id, String name, String byId, String byName, Instant now) {
        return new ApplicationCase(applicationId, stage, id, name, byId, byName, now, stageEnteredAt, escalationLevel,
                escalatedAt, escalationReason, escalationTargetIds, escalationTargetNames, returnedById,
                returnedByName, returnedAt, returnNote, returnCount, now, version);
    }

    /** Back to the pool. */
    public ApplicationCase release(Instant now) {
        return new ApplicationCase(applicationId, stage, null, null, null, null, null, stageEnteredAt,
                escalationLevel, escalatedAt, escalationReason, escalationTargetIds, escalationTargetNames,
                returnedById, returnedByName, returnedAt, returnNote, returnCount, now, version);
    }

    /** Raises the escalation to {@code level} (never lowers it). */
    public ApplicationCase escalate(int level, String reason, List<String> targetIds, List<String> targetNames,
                                    Instant now) {
        if (level <= escalationLevel) {
            return this;
        }
        return new ApplicationCase(applicationId, stage, assigneeId, assigneeName, assignedById, assignedByName,
                assignedAt, stageEnteredAt, level, now, reason, targetIds, targetNames, returnedById, returnedByName,
                returnedAt, returnNote, returnCount, now, version);
    }

    /** The approver sent the application back to step 1. */
    public ApplicationCase returned(String byId, String byName, String note, Instant now) {
        return new ApplicationCase(applicationId, stage, assigneeId, assigneeName, assignedById, assignedByName,
                assignedAt, stageEnteredAt, escalationLevel, escalatedAt, escalationReason, escalationTargetIds,
                escalationTargetNames, byId, byName, now, note, returnCount + 1, now, version);
    }

    /** Step 1 was done again: the "returned by approver" banner goes, the count stays. */
    public ApplicationCase withoutReturn(Instant now) {
        return new ApplicationCase(applicationId, stage, assigneeId, assigneeName, assignedById, assignedByName,
                assignedAt, stageEnteredAt, escalationLevel, escalatedAt, escalationReason, escalationTargetIds,
                escalationTargetNames, null, null, null, null, returnCount, now, version);
    }

    /** Whether {@code userId} is one of the people the current escalation went to (they may take over the case). */
    public boolean escalatedTo(String userId) {
        return escalationLevel > 0 && escalationTargetIds.contains(userId);
    }
}
