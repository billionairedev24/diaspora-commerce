package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ReviewAction;

/**
 * A reviewer (APPROVE, FLAG, REQUEST_INFO) or final-approver (FINAL_APPROVE, SEND_BACK, REJECT) decision.
 *
 * @param failedChecks   FLAG: the checks that failed, e.g. "FMC OTI licence"
 * @param requestedItems REQUEST_INFO: documents or fields the applicant must provide
 */
public record ReviewCommand(ReviewAction action, String reasonCode, String note, List<String> failedChecks,
                            List<String> requestedItems) {

    public ReviewCommand {
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        requestedItems = requestedItems == null ? List.of() : List.copyOf(requestedItems);
    }
}
