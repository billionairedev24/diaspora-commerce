package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.port.Page;

/** Two-step staff review: a reviewer, then a different final approver. */
public interface ReviewService {

    /** Review queue (any of applications:review, :approve, :assign), longest wait first. */
    Page<ApplicationSummary> queue(QueueRequest request);

    /** The classic queue: statuses and kind only. */
    default Page<ApplicationSummary> queue(List<ApplicationStatus> statuses, OrgKind kind, int page, int size) {
        return queue(new QueueRequest(null, statuses, kind, null, null, false, false, false, page, size));
    }

    /** Full dossier. Tax ID last 4 and DOB year only unless the caller has applications:pii (logged). */
    ApplicationView detail(String applicationId);

    /** Opens an uploaded document; every view is written to the access log. */
    DocumentView viewDocument(String applicationId, String assetId);

    /** Step 1 (applications:review): approve, flag or request info. */
    ApplicationView review(String applicationId, ReviewCommand command);

    /** Step 2 (applications:approve): final approve, send back or reject. Must not be the reviewer. */
    ApplicationView decide(String applicationId, ReviewCommand command);

    List<ReasonCode> reasonCodes();

    /**
     * A queue request. {@code assignee} is a person's id or "me"; {@code queue} null means the classic status filter.
     */
    record QueueRequest(CaseQueue queue, List<ApplicationStatus> statuses, OrgKind kind, ReviewStage stage,
                        String assignee, boolean unassigned, boolean overdue, boolean escalated, int page, int size) {
    }
}
