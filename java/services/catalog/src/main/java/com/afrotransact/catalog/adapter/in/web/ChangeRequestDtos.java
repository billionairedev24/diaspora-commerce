package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.ChangeRequestService.ChangeRequestView;
import com.afrotransact.catalog.application.ChangeRequestService.DecisionCommand;
import com.afrotransact.catalog.application.ChangeRequestService.FileCommand;
import com.afrotransact.catalog.application.GtinExemptionService.ExemptionView;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ReviewQueueService.QueueItem;
import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.DuplicateHints;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of change requests, no-barcode exemptions and the staff review queue. */
final class ChangeRequestDtos {

    private ChangeRequestDtos() {
    }

    @Schema(name = "ChangeRequestInput")
    record ChangeRequestInput(
            @NotNull ChangeRequestType type,
            @Size(max = 60) @Schema(description = "The attribute key for ATTRIBUTE; the image slot (MAIN, OTHER) for "
                    + "image types; otherwise empty") String field,
            @Schema(description = "By type. TITLE, DESCRIPTION, BRAND: {value}. ATTRIBUTE: {key, value}. CATEGORY: "
                    + "{categoryId}. IMAGE_ADD: {url, width, height, bytes, contentType}; IMAGE_REPLACE: the same plus "
                    + "{position}. NEW_VARIANT: {options, gtin?, gtinExempt?, sellerSku?, images?}. WRONG_MATCH: {gtin, "
                    + "variantAtin, correctGtin?}. DUPLICATE_PAGE: {duplicateOfAtin}.") Map<String, Object> payload,
            @Size(max = 5000) @Schema(description = "Why, in the seller's words (shown to staff)") String note) {

        FileCommand toCommand() {
            return new FileCommand(type, field, payload, note);
        }
    }

    @Schema(name = "ChangeRequest")
    record ChangeRequestResponse(UUID id,
                                 @Schema(description = "The page ATIN the request is about") String atin,
                                 UUID productId,
                                 @Schema(description = "The page title at filing time") String title,
                                 ChangeRequestType type, String field, Map<String, Object> payload, String note,
                                 ChangeRequestStatus status, String requestedByOrg, String requestedBy,
                                 Instant requestedAt,
                                 @Schema(description = "Business-day SLA from filing") Instant slaDueAt,
                                 boolean overdue, long ageHours, String decidedBy, Instant decidedAt,
                                 @Schema(description = "Staff reason, shown to the requester") String reason,
                                 @Schema(description = "CORRECT_GTIN, MOVE_VARIANT, SPLIT_VARIANT or MERGE when staff "
                                         + "resolved a WRONG_MATCH or DUPLICATE_PAGE") String resolution,
                                 long version) {

        static ChangeRequestResponse of(ChangeRequestView v) {
            ProductChangeRequest r = v.request();
            return new ChangeRequestResponse(r.id(), r.atin(), r.productId(), v.title(), r.type(),
                    r.field().isEmpty() ? null : r.field(), r.payload(), r.note(), r.status(), r.requestedByOrg(),
                    r.requestedBy(), r.requestedAt(), r.slaDueAt(), v.overdue(), v.ageHours(), r.decidedBy(),
                    r.decidedAt(), r.reason(), r.resolution(), r.version());
        }
    }

    @Schema(name = "ChangeRequestPage")
    record ChangeRequestPageResponse(List<ChangeRequestResponse> items, long total, int page, int size) {

        static ChangeRequestPageResponse of(PageView<ChangeRequestView> p) {
            return new ChangeRequestPageResponse(p.items().stream().map(ChangeRequestResponse::of).toList(), p.total(),
                    p.page(), p.size());
        }
    }

    @Schema(name = "ChangeRequestDecisionInput")
    record DecisionInput(
            @NotNull @Schema(allowableValues = {"APPROVE", "REJECT"}) String decision,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @Schema(description = "Required to approve WRONG_MATCH or DUPLICATE_PAGE",
                    allowableValues = {"CORRECT_GTIN", "MOVE_VARIANT", "SPLIT_VARIANT", "MERGE"}) String resolution,
            @Schema(description = "CORRECT_GTIN: {gtin}. MOVE_VARIANT: {variantAtin, targetPageAtin}. SPLIT_VARIANT: "
                    + "{variantAtin}. MERGE: {survivorAtin, variantMap?}") Map<String, Object> resolutionParams,
            Long expectedVersion) {

        DecisionCommand toCommand() {
            return new DecisionCommand("APPROVE".equalsIgnoreCase(decision), reason, resolution, resolutionParams,
                    expectedVersion);
        }
    }

    @Schema(name = "ExemptionDecisionInput")
    record ExemptionDecisionInput(
            @NotNull @Schema(allowableValues = {"APPROVE", "REJECT"}) String decision,
            @NotBlank @Size(min = 3, max = 1000) String reason) {

        boolean approve() {
            return "APPROVE".equalsIgnoreCase(decision);
        }
    }

    @Schema(name = "DuplicateHint")
    record DuplicateHintResponse(String atin, String title, String brand, String mpn,
                                 @Schema(description = "0 to 1") double score,
                                 @Schema(allowableValues = {"BRAND_MPN", "TITLE"}) List<String> matchedOn) {

        static DuplicateHintResponse of(DuplicateHints.Hint h) {
            return new DuplicateHintResponse(h.atin(), h.title(), h.brand(), h.mpn(), h.score(), h.matchedOn());
        }
    }

    @Schema(name = "GtinExemptionRequest")
    record ExemptionResponse(UUID id, UUID productId, String productAtin, String title, String brand, String mpn,
                             @Schema(description = "The seller's reason for having no barcode") String sellerReason,
                             ExemptionRequirement requiredBecause, ExemptionStatus status, String requestedByOrg,
                             Instant createdAt, Instant slaDueAt, boolean overdue, long ageHours, String decidedBy,
                             Instant decidedAt, String reason,
                             @Schema(description = "What the seller reads while waiting") String sellerMessage,
                             List<DuplicateHintResponse> duplicateHints) {

        static ExemptionResponse of(ExemptionView v) {
            GtinExemptionRequest r = v.request();
            return new ExemptionResponse(r.id(), r.productId(), r.productAtin(), v.title(), v.brand(), v.mpn(),
                    r.sellerReason(), r.requiredBecause(), r.status(), r.requestedByOrg(), r.requestedAt(), r.slaDueAt(),
                    v.overdue(), v.ageHours(), r.decidedBy(), r.decidedAt(), r.reason(), v.sellerMessage(),
                    v.duplicateHints().stream().map(DuplicateHintResponse::of).toList());
        }
    }

    @Schema(name = "GtinExemptionPage")
    record ExemptionPageResponse(List<ExemptionResponse> items, long total, int page, int size) {

        static ExemptionPageResponse of(PageView<ExemptionView> p) {
            return new ExemptionPageResponse(p.items().stream().map(ExemptionResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }

    @Schema(name = "ReviewQueueItem")
    record QueueItemResponse(@Schema(allowableValues = {"CHANGE_REQUEST", "GTIN_EXEMPTION"}) String kind, UUID id,
                             String atin, String title,
                             @Schema(description = "The request type or \"No barcode\"") String summary,
                             String requestedByOrg, Instant createdAt, Instant slaDueAt, boolean overdue, long ageHours) {

        static QueueItemResponse of(QueueItem i) {
            return new QueueItemResponse(i.kind().name(), i.id(), i.atin(), i.title(), i.summary(), i.requestedByOrg(),
                    i.createdAt(), i.slaDueAt(), i.overdue(), i.ageHours());
        }
    }

    @Schema(name = "ReviewQueuePage")
    record QueuePageResponse(List<QueueItemResponse> items, long total, int page, int size) {

        static QueuePageResponse of(PageView<QueueItem> p) {
            return new QueuePageResponse(p.items().stream().map(QueueItemResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }
}
