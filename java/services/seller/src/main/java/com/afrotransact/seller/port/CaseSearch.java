package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.ReviewStage;

/**
 * A queue request. {@code me} and the two {@code can} flags say what the caller may claim; the cutoffs are "now minus
 * the stage's SLA" so that "overdue" is decided by the database.
 *
 * @param statuses    explicit statuses (the classic filter); ignored for a queue that implies them
 * @param assigneeId  only cases held by this person
 * @param unassigned  only cases nobody holds
 * @param overdue     only cases past their SLA
 * @param escalated   only escalated cases
 */
public record CaseSearch(CaseQueue queue, List<ApplicationStatus> statuses, OrgKind kind, ReviewStage stage,
                         String assigneeId, boolean unassigned, boolean overdue, boolean escalated, String me,
                         boolean canReview, boolean canApprove, Instant reviewCutoff, Instant approvalCutoff,
                         int page, int size) {

    public CaseSearch {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
