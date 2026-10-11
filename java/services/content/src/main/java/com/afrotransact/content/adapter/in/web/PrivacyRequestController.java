package com.afrotransact.content.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.content.application.PrivacyRequestService;
import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.privacy.PrivacyRequest;
import com.afrotransact.content.domain.privacy.PrivacyRequestStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Privacy requests", description = "Your Privacy Choices: access, delete, correct and opt-out requests, answered within 45 days")
@RestController
@RequestMapping("/v1/privacy/requests")
class PrivacyRequestController {

    private final PrivacyRequestService requests;
    private final Clock clock;

    PrivacyRequestController(PrivacyRequestService requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Schema(name = "PrivacyRequestSubmission")
    record SubmissionRequest(@Schema(allowableValues = {"access", "delete", "correct", "optout"}) String type,
                             @Schema(description = "Email on the requester's account") String email,
                             @Schema(description = "US state of residence (two letters, DC included)", example = "TX") String state,
                             @Schema(description = "Optional details, up to 2000 characters") String details,
                             @Schema(example = "en") String locale) {
    }

    @Schema(name = "PrivacyRequestReceipt")
    record ReceiptDto(@Schema(example = "PR-100001") String reference,
                      @Schema(allowableValues = {"access", "delete", "correct", "optout"}) String type,
                      @Schema(allowableValues = {"RECEIVED", "COMPLETED", "DENIED"}) String status,
                      Instant submittedAt, @Schema(description = "Response deadline (45 days)") Instant dueAt) {

        static ReceiptDto of(PrivacyRequest r) {
            return new ReceiptDto(r.reference(), r.type().key(), r.status().name(), r.submittedAt(), r.dueAt());
        }
    }

    @Schema(name = "PrivacyRequestRecord")
    record RecordDto(String id, @Schema(example = "PR-100001") String reference,
                     @Schema(allowableValues = {"access", "delete", "correct", "optout"}) String type,
                     String email, String state, String details,
                     @Schema(description = "Account id when the requester was signed in") String accountSubject,
                     String locale, @Schema(allowableValues = {"RECEIVED", "COMPLETED", "DENIED"}) String status,
                     boolean overdue, String resolutionNote, Instant submittedAt, Instant dueAt, Instant resolvedAt,
                     String resolvedBy, long version) {

        static RecordDto of(PrivacyRequest r, Instant now) {
            return new RecordDto(r.id().toString(), r.reference(), r.type().key(), r.email().value(), r.stateCode(),
                    r.details(), r.accountSubject().orElse(null), r.locale(), r.status().name(), r.overdue(now),
                    r.resolutionNote(), r.submittedAt(), r.dueAt(), r.resolvedAt(), r.resolvedBy(), r.version());
        }
    }

    @Schema(name = "PrivacyRequestResolution")
    record ResolutionRequest(@Schema(allowableValues = {"COMPLETED", "DENIED"}) String outcome,
                             @Schema(description = "What was done, or why the request is denied (required to deny)") String note) {
    }

    @Operation(operationId = "submitPrivacyRequest",
            summary = "Submit a privacy request (anyone; a signed-in caller's account is attached)")
    @SecurityRequirements
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReceiptDto submit(@RequestBody SubmissionRequest body) {
        return ReceiptDto.of(requests.submit(new PrivacyRequest.Submission(body.type(), body.email(), body.state(),
                body.details(), body.locale())));
    }

    @Operation(operationId = "listPrivacyRequests", summary = "Privacy requests, newest first (content:legal.view)")
    @GetMapping
    List<RecordDto> list(@RequestParam(required = false) String status) {
        Instant now = Instant.now(clock);
        return requests.list(status(status, true)).stream().map(r -> RecordDto.of(r, now)).toList();
    }

    @Operation(operationId = "getPrivacyRequest", summary = "One privacy request (content:legal.view)")
    @GetMapping("/{reference}")
    RecordDto get(@PathVariable String reference) {
        return RecordDto.of(requests.get(reference), Instant.now(clock));
    }

    @Operation(operationId = "resolvePrivacyRequest",
            summary = "Complete or deny a received request; a denial needs a reason (content:legal.edit)")
    @PostMapping("/{reference}/resolution")
    RecordDto resolve(@PathVariable String reference, @RequestBody ResolutionRequest body) {
        return RecordDto.of(requests.resolve(reference, status(body.outcome(), false), body.note()), Instant.now(clock));
    }

    private static PrivacyRequestStatus status(String raw, boolean optional) {
        if (raw == null || raw.isBlank()) {
            if (optional) {
                return null;
            }
            throw new ContentRuleViolation("content.invalid-privacy-resolution", "Choose completed or denied",
                    Map.of("outcome", "Choose completed or denied"));
        }
        try {
            return PrivacyRequestStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ContentRuleViolation("content.invalid-privacy-status", "Unknown status " + raw,
                    Map.of(optional ? "status" : "outcome", "One of RECEIVED, COMPLETED, DENIED"));
        }
    }
}
