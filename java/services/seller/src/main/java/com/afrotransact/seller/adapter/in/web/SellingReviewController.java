package com.afrotransact.seller.adapter.in.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.afrotransact.seller.application.DocumentView;
import com.afrotransact.seller.application.ReviewCommand;
import com.afrotransact.seller.application.SellingReviewService;
import com.afrotransact.seller.application.SellingReviewService.QueueRequest;
import com.afrotransact.seller.application.SellingViews.SellingCaseView;
import com.afrotransact.seller.application.SellingViews.SellingCaseSummary;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.ReviewStage;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Selling approval cases", description = "Staff queue for selling approvals, next to seller applications: "
        + "the same two-step review (applications:review, then a different person with applications:approve), "
        + "assignment, SLAs and escalation. Reason codes: GET /v1/review-reason-codes.")
@RestController
class SellingReviewController {

    private static final int MAX_KEY = 128;

    private final SellingReviewService reviews;

    SellingReviewController(SellingReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(summary = "Selling approval queue (default: pending review, flagged and awaiting final approval)",
            description = "Same queues and filters as GET /v1/applications: MY, AWAITING_REVIEW, AWAITING_APPROVAL, "
                    + "ESCALATED, RETURNED, ALL; stage, assignee (a person id or me), unassigned, overdue, escalated.")
    @GetMapping("/v1/selling-applications")
    QueueResponse queue(@Parameter(description = "Comma-separated statuses") @RequestParam(required = false)
                        String status,
                        @RequestParam(required = false) String queue,
                        @RequestParam(required = false) String stage,
                        @RequestParam(required = false) String assignee,
                        @RequestParam(defaultValue = "false") boolean unassigned,
                        @RequestParam(defaultValue = "false") boolean overdue,
                        @RequestParam(defaultValue = "false") boolean escalated,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "50") int size) {
        List<ApplicationStatus> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(s -> parse(ApplicationStatus.class, s, "status")).toList();
        Page<SellingCaseSummary> p = reviews.queue(new QueueRequest(parse(CaseQueue.class, queue, "queue"), statuses,
                parse(ReviewStage.class, stage, "stage"), blankToNull(assignee), unassigned, overdue, escalated,
                page, size));
        return new QueueResponse(p.items(), p.page(), p.size(), p.total());
    }

    @Operation(summary = "One selling approval case: documents, timeline, who has it, the seller's current approval")
    @GetMapping("/v1/selling-applications/{id}")
    SellingCaseView detail(@PathVariable String id) {
        return reviews.detail(id);
    }

    @Operation(summary = "Open an uploaded document (logged in the access log)")
    @GetMapping("/v1/selling-applications/{id}/documents/{assetId}")
    DocumentView document(@PathVariable String id, @PathVariable String assetId) {
        return reviews.viewDocument(id, assetId);
    }

    @Operation(summary = "Who has the case: CLAIM, ASSIGN, RELEASE or ESCALATE (same rules as applications)")
    @PostMapping("/v1/selling-applications/{id}/assignment")
    SellingCaseView assignment(@PathVariable String id, @Valid @RequestBody AssignmentBody body) {
        String action = body.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "CLAIM" -> reviews.claim(id, body.actorName());
            case "ASSIGN" -> reviews.assign(id, body.assigneeId(), body.note(), body.actorName());
            case "RELEASE" -> reviews.release(id, body.note(), body.actorName());
            case "ESCALATE" -> reviews.escalate(id, body.reason(), body.actorName());
            default -> throw new SellerRuleViolation("seller.invalid-action", "Unknown assignment action "
                    + body.action());
        };
    }

    @Operation(summary = "People the case can be assigned to now")
    @GetMapping("/v1/selling-applications/{id}/assignment/candidates")
    List<ReviewController.Person> candidates(@PathVariable String id) {
        return reviews.candidates(id).stream().map(p -> new ReviewController.Person(p.id(), p.name())).toList();
    }

    @Operation(summary = "Step 1 — reviewer decision: APPROVE, FLAG (failedChecks) or REQUEST_INFO (requestedItems)",
            description = "Idempotent like application review: a retry with the same Idempotency-Key answers the "
                    + "current case and changes nothing. REQUEST_INFO note is the message the seller reads.")
    @PostMapping("/v1/selling-applications/{id}/review")
    SellingCaseView review(@PathVariable String id, @Valid @RequestBody DecisionBody body,
                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return reviews.review(id, body.toCommand(key));
    }

    @Operation(summary = "Step 2 — final decision, not by the reviewer: FINAL_APPROVE, SEND_BACK, REJECT, REQUEST_INFO or DISQUALIFY",
            description = "DISQUALIFY is \"cannot apply\": not appealable, needs a note the seller reads and "
                    + "reapplyAfter (a date after today). FINAL_APPROVE gives the seller the approval until the "
                    + "earliest document expiry and restores listings that were paused for it.")
    @PostMapping("/v1/selling-applications/{id}/decision")
    SellingCaseView decision(@PathVariable String id, @Valid @RequestBody DecisionBody body,
                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return reviews.decide(id, body.toCommand(key), body.reapplyAfter());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String s, String what) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Unknown " + what + " " + s);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Schema(name = "SellingDecision")
    record DecisionBody(@NotBlank @Schema(allowableValues = {"APPROVE", "FLAG", "REQUEST_INFO", "FINAL_APPROVE",
            "SEND_BACK", "REJECT", "DISQUALIFY"}) String action, @NotBlank String reasonCode,
                        @Size(max = 2000) String note, List<String> failedChecks, List<String> requestedItems,
                        @Schema(description = "DISQUALIFY: the earliest day the seller may apply again")
                        LocalDate reapplyAfter, @Size(max = 200) String actorName) {

        ReviewCommand toCommand(String key) {
            ReviewAction a;
            try {
                a = ReviewAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new SellerRuleViolation("seller.invalid-action", "Unknown action " + action);
            }
            if (key != null && key.trim().length() > MAX_KEY) {
                throw new SellerRuleViolation("seller.invalid-idempotency-key",
                        "Idempotency-Key must be at most " + MAX_KEY + " characters");
            }
            return new ReviewCommand(a, reasonCode, note, failedChecks, requestedItems, key, actorName);
        }
    }

    @Schema(name = "SellingAssignmentRequest")
    record AssignmentBody(@NotBlank @Schema(allowableValues = {"CLAIM", "ASSIGN", "RELEASE", "ESCALATE"})
                          String action, String assigneeId, @Size(max = 500) String note,
                          @Size(max = 500) String reason, @Size(max = 200) String actorName) {
    }

    @Schema(name = "SellingApplicationQueue")
    record QueueResponse(List<SellingCaseSummary> items, int page, int size, long total) {
    }
}
