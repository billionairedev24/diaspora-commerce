package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

/** Queue row for reviewers; {@code workflow} says who holds the application and how long (see {@link ApplicationCaseView}).
 *
 *  {@code reviewStep} is 1 (reviewer) or 2 (final approver); 0 when not in review. */
public record ApplicationSummary(String id, String reference, String orgId, String kind, String status,
                                 String displayName, String legalName, String state, String planCode,
                                 int reviewStep, String reviewerId, List<String> failedChecks, Instant submittedAt,
                                 Instant updatedAt, ApplicationCaseView workflow) {

    public ApplicationSummary {
        failedChecks = List.copyOf(failedChecks);
    }
}
