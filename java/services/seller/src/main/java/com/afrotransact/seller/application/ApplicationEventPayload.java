package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.TimelineEntry;

/** Payload of seller.application.* events. */
public record ApplicationEventPayload(String applicationId, String reference, String orgId, String kind,
                                      String applicantUserId, String status, String action, String actorId,
                                      String reasonCode, String note, List<String> failedChecks,
                                      List<String> requestedItems, String reviewerId, String finalApproverId,
                                      String displayName, int submissionCount, Instant occurredAt) {

    static ApplicationEventPayload of(Application a, TimelineEntry t) {
        return new ApplicationEventPayload(a.id(), a.reference(), a.orgId(), a.kind().name(), a.applicantUserId(),
                a.status().name(), t.action().name(), t.actorId(), t.reasonCode(), t.note(), a.failedChecks(),
                a.requestedItems(), a.reviewerId(), a.finalApproverId(),
                a.data().business() == null ? null : a.data().business().storeName(), a.submissionCount(),
                t.occurredAt());
    }
}
