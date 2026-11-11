package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;

/**
 * Who holds an application during review: claim, assign, release, take over, escalate by hand, and the hook that
 * moves the case when the application changes stage. The review decisions themselves stay in {@link ReviewService}.
 */
public interface ReviewAssignmentService {

    /** An eligible person takes an unassigned case. Repeating it when the case is already yours changes nothing. */
    ApplicationCaseView claim(String applicationId, String actorName);

    /** Gives (or takes over for oneself, or reassigns) the case to an eligible person. */
    ApplicationCaseView assign(String applicationId, String assigneeId, String note, String actorName);

    /** Back to the pool. */
    ApplicationCaseView release(String applicationId, String note, String actorName);

    /** Raises the escalation one level now, with a reason (the holder or a supervisor). */
    ApplicationCaseView escalate(String applicationId, String reason, String actorName);

    /** People the case could be assigned to now (they hold the stage permission and may hold this application). */
    /** The scheduled check for one case: raises the escalation one level when it is overdue or untaken. Idempotent. */
    boolean escalateIfDue(String applicationId, Instant now);

    List<StaffPerson> candidates(String applicationId);

    ReviewSettings settings();

    /** The case as {@code actor} sees it; {@code workflow} may be null (no row yet: unassigned, wait since the last change). */
    ApplicationCaseView viewFor(Application app, ApplicationCase workflow, Actor actor, ReviewSettings settings);

    /**
     * The application moved (submit, step 1, step 2, send back, return to the applicant): ends the assignee and the
     * escalation, starts the wait at the new stage, applies auto-assignment, records a send-back and tells staff.
     * Called inside the transaction of the change.
     *
     * @param cause the action that moved it (SUBMITTED, RESUBMITTED, APPROVE, FLAG, REQUEST_INFO, FINAL_APPROVE,
     *              SEND_BACK, REJECT)
     */
    void transitioned(Application before, Application after, ReviewAction cause, String actorId, String actorName,
                      String note);
}
