package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.port.Page;

/** Two-step staff review: a reviewer, then a different final approver. */
public interface ReviewService {

    /** Review queue (applications:review), oldest submission first. */
    Page<ApplicationSummary> queue(List<ApplicationStatus> statuses, OrgKind kind, int page, int size);

    /** Full dossier. Tax ID last 4 and DOB year only unless the caller has applications:pii (logged). */
    ApplicationView detail(String applicationId);

    /** Opens an uploaded document; every view is written to the access log. */
    DocumentView viewDocument(String applicationId, String assetId);

    /** Step 1 (applications:review): approve, flag or request info. */
    ApplicationView review(String applicationId, ReviewCommand command);

    /** Step 2 (applications:approve): final approve, send back or reject. Must not be the reviewer. */
    ApplicationView decide(String applicationId, ReviewCommand command);

    List<ReasonCode> reasonCodes();
}
