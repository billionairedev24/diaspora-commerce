package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

/**
 * Who has an application and for how long, as one viewer sees it. Anyone who can view applications sees all of it;
 * the {@code can*} flags say what this viewer may do (actions stay gated by permission).
 *
 * @param stage        REVIEW or APPROVAL; null while no staff member holds the application
 * @param holder       "with Amina Bello" or "any reviewer" / "any approver" (names, never ids)
 * @param waitingSeconds time at this stage
 * @param slaState     ON_TIME, DUE_SOON or OVERDUE (null without a stage)
 * @param slaDueAt     when the stage's service level ends
 * @param escalatedTo  names of the people the escalation went to
 * @param returnedByName / returnedAt / returnNote the last time an approver sent it back (the banner), null when none
 * @param returnCount  how many times approvers sent it back
 * @param mine         the viewer holds it
 */
public record ApplicationCaseView(String stage, String holder, String assigneeId, String assigneeName,
                                  Instant assignedAt, String assignedByName, Instant stageEnteredAt,
                                  long waitingSeconds, String slaState, Instant slaDueAt, int escalationLevel,
                                  Instant escalatedAt, String escalationReason, List<String> escalatedTo,
                                  String returnedByName, Instant returnedAt, String returnNote, int returnCount,
                                  boolean mine, boolean canClaim, boolean canAssign, boolean canRelease,
                                  boolean canEscalate, boolean canTakeOver) {

    public ApplicationCaseView {
        escalatedTo = escalatedTo == null ? List.of() : List.copyOf(escalatedTo);
    }
}
