package com.afrotransact.seller.adapter.in.web;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.afrotransact.seller.adapter.in.web.OnboardingDtos.ApplicationResponse;
import com.afrotransact.seller.application.ApplicationSummary;
import com.afrotransact.seller.application.DocumentView;
import com.afrotransact.seller.application.ReviewCommand;
import com.afrotransact.seller.application.ReviewService;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Application review", description = "Two-step staff review. Step 1 (applications:review): approve → "
        + "AWAITING_FINAL_APPROVAL, flag → FLAGGED, request info → INFO_REQUESTED. Step 2 (applications:approve, a "
        + "different person): final approve → APPROVED (seller.org.approved), send back → PENDING_REVIEW, reject.")
@RestController
class ReviewController {

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Review queue (default: pending review, flagged and awaiting final approval)")
    @GetMapping("/v1/applications")
    QueueResponse reviewQueue(@Parameter(description = "Comma-separated statuses") @RequestParam(required = false)
                        String status,
                        @RequestParam(required = false) String kind,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "50") int size) {
        List<ApplicationStatus> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(s -> parseStatus(s.trim())).toList();
        Page<ApplicationSummary> p = reviews.queue(statuses, kind == null ? null : Inputs.kind(kind), page, size);
        return new QueueResponse(p.items(), p.page(), p.size(), p.total());
    }

    @Operation(summary = "Full application dossier",
            description = "SSN/EIN shown as last 4 and dates of birth as year only, unless the reviewer has "
                    + "applications:pii (the view is logged).")
    @GetMapping("/v1/applications/{id}")
    ApplicationResponse applicationDetail(@PathVariable String id) {
        return ApplicationResponse.of(reviews.detail(id));
    }

    @Operation(summary = "Open an uploaded document (logged in the access log)")
    @GetMapping("/v1/applications/{id}/documents/{assetId}")
    DocumentView viewDocument(@PathVariable String id, @PathVariable String assetId) {
        return reviews.viewDocument(id, assetId);
    }

    @Operation(summary = "Step 1 — reviewer decision: APPROVE, FLAG (failedChecks) or REQUEST_INFO (requestedItems)")
    @PostMapping("/v1/applications/{id}/review")
    ApplicationResponse reviewApplication(@PathVariable String id, @Valid @RequestBody DecisionRequest body) {
        return ApplicationResponse.of(reviews.review(id, body.toCommand()));
    }

    @Operation(summary = "Step 2 — final decision: FINAL_APPROVE, SEND_BACK or REJECT (not by the reviewer)")
    @PostMapping("/v1/applications/{id}/decision")
    ApplicationResponse finalDecision(@PathVariable String id, @Valid @RequestBody DecisionRequest body) {
        return ApplicationResponse.of(reviews.decide(id, body.toCommand()));
    }

    @Operation(summary = "Review reason codes")
    @GetMapping("/v1/review-reason-codes")
    List<ReasonCode> listReasonCodes() {
        return reviews.reasonCodes();
    }

    private static ApplicationStatus parseStatus(String s) {
        try {
            return ApplicationStatus.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Unknown status " + s);
        }
    }

    @Schema(name = "ReviewDecision")
    record DecisionRequest(@NotBlank @Schema(allowableValues = {"APPROVE", "FLAG", "REQUEST_INFO", "FINAL_APPROVE",
                               "SEND_BACK", "REJECT"}) String action,
                           @NotBlank String reasonCode, @Size(max = 2000) String note, List<String> failedChecks,
                           List<String> requestedItems) {

        ReviewCommand toCommand() {
            ReviewAction a;
            try {
                a = ReviewAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new SellerRuleViolation("seller.invalid-action", "Unknown action " + action);
            }
            return new ReviewCommand(a, reasonCode, note, failedChecks, requestedItems);
        }
    }

    @Schema(name = "ApplicationQueue")
    record QueueResponse(List<ApplicationSummary> items, int page, int size, long total) {
    }
}
