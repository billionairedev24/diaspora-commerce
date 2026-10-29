package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ReviewAction;

/**
 * A reviewer (APPROVE, FLAG, REQUEST_INFO) or final-approver (FINAL_APPROVE, SEND_BACK, REJECT) decision.
 *
 * @param failedChecks   FLAG: the checks that failed, e.g. "FMC OTI licence"
 * @param requestedItems REQUEST_INFO: documents or fields the applicant must provide
 * @param requestKey     the request's Idempotency-Key (optional): a retry with the same key replays the decision
 * @param actorName      the staff member's display name, recorded on the timeline (optional, display only)
 */
public record ReviewCommand(ReviewAction action, String reasonCode, String note, List<String> failedChecks,
                            List<String> requestedItems, String requestKey, String actorName) {

    public ReviewCommand {
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        requestedItems = requestedItems == null ? List.of() : List.copyOf(requestedItems);
        requestKey = requestKey == null || requestKey.isBlank() ? null : requestKey.trim();
        actorName = actorName == null || actorName.isBlank() ? null : actorName.trim();
    }

    public ReviewCommand(ReviewAction action, String reasonCode, String note, List<String> failedChecks,
                         List<String> requestedItems) {
        this(action, reasonCode, note, failedChecks, requestedItems, null, null);
    }
}
