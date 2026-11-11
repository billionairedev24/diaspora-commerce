package com.afrotransact.seller.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.seller.application.SellingViews.SellingCaseView;
import com.afrotransact.seller.application.SellingViews.SellingCaseSummary;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;

/**
 * Staff side of selling approvals: the same case workflow as seller applications (reviewer, then a different
 * approver; claim, assign, release, escalate; SLAs from the application review settings), for the case type "Selling
 * approval". Permissions are applications:review, applications:approve and applications:assign.
 */
public interface SellingReviewService {

    Page<SellingCaseSummary> queue(QueueRequest request);

    SellingCaseView detail(String applicationId);

    /** Opens a document (logged in the access log) and returns a short-lived URL. */
    DocumentView viewDocument(String applicationId, String assetId);

    /** Step 1 (applications:review): APPROVE, FLAG or REQUEST_INFO. */
    SellingCaseView review(String applicationId, ReviewCommand command);

    /**
     * Step 2 (applications:approve, not the reviewer): FINAL_APPROVE, SEND_BACK, REJECT, REQUEST_INFO or DISQUALIFY
     * ("cannot apply": not appealable, {@code reapplyAfter} required).
     */
    SellingCaseView decide(String applicationId, ReviewCommand command, LocalDate reapplyAfter);

    List<ReasonCode> reasonCodes();

    SellingCaseView claim(String applicationId, String actorName);

    SellingCaseView assign(String applicationId, String assigneeId, String note, String actorName);

    SellingCaseView release(String applicationId, String note, String actorName);

    SellingCaseView escalate(String applicationId, String reason, String actorName);

    List<StaffPerson> candidates(String applicationId);

    /** A new or resubmitted application reached step 1: creates its case and tells the pool (or the auto-assigned reviewer). Caller holds the row lock. */
    void opened(SellingApplication application);

    /** The scheduled check for one case: raises the escalation one level when overdue or untaken. Idempotent. */
    boolean escalateIfDue(String applicationId, Instant now);

    /** Runs the escalation check over the open cases; returns how many were raised. */
    int runDueEscalations();

    record QueueRequest(CaseQueue queue, List<ApplicationStatus> statuses, ReviewStage stage, String assignee,
                        boolean unassigned, boolean overdue, boolean escalated, int page, int size) {
    }
}
