package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

/**
 * Payload of {@code seller.application.assigned} and {@code seller.application.escalated}: an internal staff
 * notification, never sent to the applicant. {@code recipientUserIds} are the staff to tell (one event, many people).
 *
 * @param reason ASSIGNED, AUTO_ASSIGNED, READY_FOR_APPROVAL, SENT_BACK or ESCALATED
 * @param level  escalation level (0 for assignments)
 */
public record AssignmentEventPayload(String applicationId, String reference, String displayName, String kind,
                                     String stage, String reason, String assigneeId, String assigneeName,
                                     String actorName, List<String> recipientUserIds, int level, String note,
                                     Instant occurredAt) {

    public AssignmentEventPayload {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }
}
