package com.afrotransact.seller.adapter.in.web;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.afrotransact.seller.adapter.in.web.OnboardingDtos.ApplicationResponse;
import com.afrotransact.seller.application.ApplicationSummary;
import com.afrotransact.seller.application.DocumentView;
import com.afrotransact.seller.application.ReviewAssignmentService;
import com.afrotransact.seller.application.ReviewCommand;
import com.afrotransact.seller.application.ReviewService;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.CaseQueue;
import com.afrotransact.seller.domain.ReasonCode;
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

@Tag(name = "Application review", description = "Two-step staff review. Step 1 (applications:review): approve → "
        + "AWAITING_FINAL_APPROVAL, flag → FLAGGED, request info → INFO_REQUESTED. Step 2 (applications:approve, a "
        + "different person): final approve → APPROVED (seller.org.approved), send back → PENDING_REVIEW, reject.")
@RestController
class ReviewController {

    private static final String REPLAY = "Idempotent: a retry with the same Idempotency-Key, or the same person "
            + "repeating the decision that put the application in its current state, answers 200 with the current "
            + "application and changes nothing (no timeline entry, no events, no email). A decision that is no longer "
            + "possible answers 409: seller.already-decided / seller.already-reviewed say what was decided, by whom "
            + "and when (properties decision, decidedBy, decidedAt); seller.idempotency-key-reused when the key "
            + "named a different decision.";
    private static final String KEY = "Optional, 8–128 characters; the same key on every retry of one decision";
    private static final int MAX_KEY = 128;

    private final ReviewService reviews;
    private final ReviewAssignmentService assignments;

    ReviewController(ReviewService reviews, ReviewAssignmentService assignments) {
        this.reviews = reviews;
        this.assignments = assignments;
    }

    @Operation(summary = "Review queue (default: pending review, flagged and awaiting final approval)",
            description = "Visible to anyone who can view applications (applications:review, :approve or :assign), "
                    + "whether or not the case is in their own purview. Every row says who holds it and for how "
                    + "long (workflow). queue: MY (assigned to me, plus unassigned cases I may claim), "
                    + "AWAITING_REVIEW, AWAITING_APPROVAL, ESCALATED, RETURNED (with the applicant), ALL. "
                    + "Filters: stage, assignee (a person id or me), unassigned, overdue, escalated.")
    @GetMapping("/v1/applications")
    QueueResponse reviewQueue(@Parameter(description = "Comma-separated statuses") @RequestParam(required = false)
                        String status,
                        @RequestParam(required = false) String kind,
                        @Parameter(description = "MY, AWAITING_REVIEW, AWAITING_APPROVAL, ESCALATED, RETURNED or ALL")
                        @RequestParam(required = false) String queue,
                        @Parameter(description = "REVIEW or APPROVAL") @RequestParam(required = false) String stage,
                        @Parameter(description = "A person's id, or me") @RequestParam(required = false)
                        String assignee,
                        @RequestParam(defaultValue = "false") boolean unassigned,
                        @RequestParam(defaultValue = "false") boolean overdue,
                        @RequestParam(defaultValue = "false") boolean escalated,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "50") int size) {
        List<ApplicationStatus> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(s -> parseStatus(s.trim())).toList();
        Page<ApplicationSummary> p = reviews.queue(new ReviewService.QueueRequest(parseQueue(queue), statuses,
                kind == null ? null : Inputs.kind(kind), parseStage(stage), blankToNull(assignee), unassigned,
                overdue, escalated, page, size));
        return new QueueResponse(p.items(), p.page(), p.size(), p.total());
    }

    @Operation(summary = "Who has the case: CLAIM, ASSIGN (also reassign and take over), RELEASE or ESCALATE",
            description = "CLAIM: an eligible person takes an unassigned case. ASSIGN (assigneeId): a supervisor "
                    + "(applications:assign), the person an escalation went to, or an approver at the approval stage "
                    + "gives it to an eligible person: they hold the stage permission, are not the applicant and, for "
                    + "approval, are not the step 1 reviewer. RELEASE: back to the pool (the holder, or a manager). "
                    + "ESCALATE (reason): raises the escalation one level now (the holder or a supervisor). Repeats "
                    + "change nothing. Every change is on the timeline with names.")
    @PostMapping("/v1/applications/{id}/assignment")
    ApplicationResponse assignment(@PathVariable String id, @Valid @RequestBody AssignmentRequest body) {
        String action = body.action().trim().toUpperCase(Locale.ROOT);
        switch (action) {
            case "CLAIM" -> assignments.claim(id, body.actorName());
            case "ASSIGN" -> assignments.assign(id, body.assigneeId(), body.note(), body.actorName());
            case "RELEASE" -> assignments.release(id, body.note(), body.actorName());
            case "ESCALATE" -> assignments.escalate(id, body.reason(), body.actorName());
            default -> throw new SellerRuleViolation("seller.invalid-action", "Unknown assignment action "
                    + body.action());
        }
        return ApplicationResponse.of(reviews.detail(id));
    }

    @Operation(summary = "People the case can be assigned to now (hold the stage permission, may hold this application)")
    @GetMapping("/v1/applications/{id}/assignment/candidates")
    List<Person> assignmentCandidates(@PathVariable String id) {
        return assignments.candidates(id).stream().map(p -> new Person(p.id(), p.name())).toList();
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

    @Operation(summary = "Step 1 — reviewer decision: APPROVE, FLAG (failedChecks) or REQUEST_INFO (requestedItems)",
            description = REPLAY)
    @PostMapping("/v1/applications/{id}/review")
    ApplicationResponse reviewApplication(@PathVariable String id, @Valid @RequestBody DecisionRequest body,
                                          @Parameter(description = KEY) @RequestHeader(value = "Idempotency-Key",
                                                  required = false) String key) {
        return ApplicationResponse.of(reviews.review(id, body.toCommand(key)));
    }

    @Operation(summary = "Step 2 — final decision: FINAL_APPROVE, SEND_BACK, REJECT or REQUEST_INFO (requestedItems; returns it to the applicant), not by the reviewer",
            description = REPLAY)
    @PostMapping("/v1/applications/{id}/decision")
    ApplicationResponse finalDecision(@PathVariable String id, @Valid @RequestBody DecisionRequest body,
                                      @Parameter(description = KEY) @RequestHeader(value = "Idempotency-Key",
                                              required = false) String key) {
        return ApplicationResponse.of(reviews.decide(id, body.toCommand(key)));
    }

    @Operation(summary = "Review reason codes")
    @GetMapping("/v1/review-reason-codes")
    List<ReasonCode> listReasonCodes() {
        return reviews.reasonCodes();
    }

    private static CaseQueue parseQueue(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return CaseQueue.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Unknown queue " + s);
        }
    }

    private static ReviewStage parseStage(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ReviewStage.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Unknown stage " + s);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
                           List<String> requestedItems,
                           @Size(max = 200) @Schema(description = "The staff member's display name, recorded on the "
                                   + "timeline and used in \"already approved by …\" messages (display only)")
                           String actorName) {

        ReviewCommand toCommand(String key) {
            ReviewAction a;
            try {
                a = ReviewAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new SellerRuleViolation("seller.invalid-action", "Unknown action " + action);
            }
            if (key != null && !key.isBlank() && key.trim().length() > MAX_KEY) {
                throw new SellerRuleViolation("seller.invalid-idempotency-key",
                        "Idempotency-Key must be at most " + MAX_KEY + " characters");
            }
            return new ReviewCommand(a, reasonCode, note, failedChecks, requestedItems, key, actorName);
        }
    }

    @Schema(name = "AssignmentRequest")
    record AssignmentRequest(@NotBlank @Schema(allowableValues = {"CLAIM", "ASSIGN", "RELEASE", "ESCALATE"})
                             String action, String assigneeId, @Size(max = 500) String note,
                             @Size(max = 500) String reason,
                             @Size(max = 200) @Schema(description = "The staff member's display name, recorded on "
                                     + "the timeline (display only)") String actorName) {
    }

    @Schema(name = "StaffPerson")
    record Person(String id, String name) {
    }

    @Schema(name = "ApplicationQueue")
    record QueueResponse(List<ApplicationSummary> items, int page, int size, long total) {
    }
}
