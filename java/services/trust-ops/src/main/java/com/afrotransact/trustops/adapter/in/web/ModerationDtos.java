package com.afrotransact.trustops.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.adapter.in.web.CommonDtos.SlaResponse;
import com.afrotransact.trustops.application.ModerationItemView;
import com.afrotransact.trustops.domain.AiFlag;
import com.afrotransact.trustops.domain.ListingFlagSource;
import com.afrotransact.trustops.domain.ListingReport;
import com.afrotransact.trustops.domain.ModerationItem;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationPriority;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.domain.ReviewSubmission;
import com.afrotransact.trustops.domain.TriageHint;
import com.afrotransact.trustops.domain.TriageStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Moderation queue DTOs. */
final class ModerationDtos {

    private ModerationDtos() {
    }

    @Schema(description = "AI triage hint (advisory; absent when AI is off or unavailable)")
    record TriageResponse(String riskLevel, String summary, List<AiFlag> flags, String model) {

        static TriageResponse of(TriageHint h) {
            return h == null ? null : new TriageResponse(h.riskLevel(), h.summary(), h.flags(), h.model());
        }
    }

    @Schema(description = "Why a live listing is queued: the first report or flag and how many arrived")
    record ReportResponse(UUID flagId, ListingFlagSource source, ModerationReasonCode reasonCode, String details,
                          String reporterId, String riskLevel, int reports) {

        static ReportResponse of(ListingReport r) {
            return r == null ? null : new ReportResponse(r.flagId(), r.source(), r.reasonCode(), r.details(),
                    r.reporterId(), r.riskLevel(), r.reports());
        }
    }

    @Schema(description = "The customer review a SERVICE_REVIEW item is about (bookings-service)")
    record ReviewResponse(UUID bookingId, UUID serviceId, String serviceName,
                          @Schema(description = "Author (token subject); never public") String customerId,
                          String reviewerName, int rating, String text, Instant submittedAt) {

        static ReviewResponse of(ReviewSubmission r) {
            return r == null ? null : new ReviewResponse(r.bookingId(), r.serviceId(), r.serviceName(),
                    r.customerId(), r.reviewerName(), r.rating(), r.text(), r.submittedAt());
        }
    }

    @Schema(description = "An item in the moderation queue: a listing (productId set) or a customer review of a "
            + "booking (review set, sellerOrgId = the provider, title = a one-line summary)")
    record ModerationItemResponse(UUID id, UUID productId, String atin, String sellerOrgId, String ownerKind,
                                  String title, String productTypeCode, ModerationItemType type,
                                  ReportResponse report, ReviewResponse review, ModerationStatus status,
                                  ModerationPriority priority, TriageStatus triageStatus, TriageResponse triage,
                                  Instant enqueuedAt, SlaResponse sla, String assignee, Instant assignedAt,
                                  boolean escalated, String escalationReason, Instant escalatedAt, String decidedBy,
                                  Instant decidedAt, ModerationReasonCode reasonCode, String decisionNote,
                                  long version) {

        static ModerationItemResponse of(ModerationItemView v) {
            ModerationItem i = v.item();
            ProductSubmission p = i.product();
            return new ModerationItemResponse(i.id(), p == null ? null : p.productId(), p == null ? null : p.atin(),
                    i.orgId(), p == null ? null : p.ownerKind(), i.title(), p == null ? null : p.productTypeCode(),
                    i.type(), ReportResponse.of(i.report()), ReviewResponse.of(i.review()), i.status(), i.priority(),
                    i.triageStatus(), TriageResponse.of(i.triage()), i.enqueuedAt(),
                    i.open() ? SlaResponse.of(v.sla()) : null,
                    i.assignee(), i.assignedAt(), i.escalated(), i.escalationReason(), i.escalatedAt(),
                    i.decidedBy(), i.decidedAt(), i.reasonCode(), i.decisionNote(), i.version());
        }
    }

    @Schema(description = "Reason code with its label")
    record ReasonCodeResponse(ModerationReasonCode code, String label) {
    }

    @Schema(description = "Assign to a reviewer; omit assignee to take it yourself")
    record AssignRequest(@Size(max = 120) String assignee) {
    }

    @Schema(description = "Escalate to senior review")
    record EscalateRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(description = "Allow the item (optional note): approves a submission, keeps a live listing (dismissing "
            + "the reports) or publishes a review")
    record ApproveRequest(@Size(max = 1000) String note) {
    }

    @Schema(description = "Remove the item (reject a submission, take a live listing down, reject a review): reason "
            + "code for the item's kind required, note required for OTHER")
    record RejectRequest(@NotNull ModerationReasonCode reasonCode, @Size(max = 1000) String note) {
    }
}
