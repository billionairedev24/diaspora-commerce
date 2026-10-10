package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.seller.application.AccountHealthView;
import com.afrotransact.seller.application.ComplianceService;
import com.afrotransact.seller.application.RequirementItem;
import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.SellerRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stripe compliance", description = "Account health for sellers (owner / finance) and the staff "
        + "compliance console (compliance:remind, compliance:hold). Stripe is the source of truth; answers are "
        + "forwarded to Stripe and verified there.")
@RestController
class ComplianceController {

    private final ComplianceService compliance;

    ComplianceController(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @Operation(summary = "Account health: what Stripe needs, with a typed form per requirement")
    @GetMapping("/v1/orgs/{orgId}/account-health")
    AccountHealthResponse accountHealth(@PathVariable String orgId) {
        return AccountHealthResponse.of(compliance.accountHealth(orgId));
    }

    @Operation(summary = "Answer a requirement (owner / finance)",
            description = "Values follow the requirement's form. Documents are media asset ids (uploaded to Stripe "
                    + "Files with purpose account_requirement); SSNs and bank accounts are Stripe.js tokens. The "
                    + "requirement then shows as pending verification until Stripe confirms.")
    @PostMapping("/v1/orgs/{orgId}/account-health/requirements/{requirement}")
    AccountHealthResponse answerRequirement(@PathVariable String orgId, @PathVariable String requirement,
                                 @Valid @RequestBody AnswerRequest body, HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return AccountHealthResponse.of(compliance.answer(orgId, requirement, body.values(), ip,
                request.getHeader(HttpHeaders.USER_AGENT)));
    }

    @Operation(summary = "Staff: connected accounts (filter by status or open items)")
    @GetMapping("/v1/compliance/accounts")
    List<AccountHealthResponse> complianceAccounts(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "false") boolean openOnly) {
        ComplianceStatus s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = ComplianceStatus.valueOf(status.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
            } catch (IllegalArgumentException e) {
                throw new SellerRuleViolation("seller.invalid-input", "Unknown compliance status " + status);
            }
        }
        return compliance.accounts(s, openOnly).stream().map(AccountHealthResponse::of).toList();
    }

    @Operation(summary = "Staff: one account with requirements, Stripe updates and messages sent")
    @GetMapping("/v1/compliance/accounts/{accountId}")
    AccountHealthResponse complianceAccount(@PathVariable String accountId) {
        return AccountHealthResponse.of(compliance.account(accountId));
    }

    @Operation(summary = "Staff: send a reminder now (email + SMS + in-app) — compliance:remind")
    @PostMapping("/v1/compliance/accounts/{accountId}/reminders")
    AccountHealthResponse remindNow(@PathVariable String accountId) {
        return AccountHealthResponse.of(compliance.remindNow(accountId));
    }

    @Operation(summary = "Staff: pause payouts — compliance:remind or compliance:hold")
    @PostMapping("/v1/compliance/accounts/{accountId}/payouts/pause")
    AccountHealthResponse pausePayouts(@PathVariable String accountId, @RequestBody(required = false) NoteRequest body) {
        return AccountHealthResponse.of(compliance.pausePayouts(accountId, body == null ? null : body.note()));
    }

    @Operation(summary = "Staff: release a payout hold — compliance:hold")
    @PostMapping("/v1/compliance/accounts/{accountId}/payouts/resume")
    AccountHealthResponse resumePayouts(@PathVariable String accountId, @RequestBody(required = false) NoteRequest body) {
        return AccountHealthResponse.of(compliance.resumePayouts(accountId, body == null ? null : body.note()));
    }

    @Schema(name = "RequirementAnswer")
    record AnswerRequest(@NotNull Map<String, String> values) {
    }

    @Schema(name = "ComplianceNote")
    record NoteRequest(@Size(max = 1000) String note) {
    }

    @Schema(name = "ComplianceHistory")
    record HistoryView(String source, String eventId, String eventType, String status, String summary,
                       String actorId, Instant occurredAt) {

        static HistoryView of(ComplianceHistoryEntry h) {
            return new HistoryView(h.source(), h.eventId(), h.eventType(), h.status().name(), h.summary(),
                    h.actorId(), h.occurredAt());
        }
    }

    @Schema(name = "AccountHealth")
    record AccountHealthResponse(String accountId, String orgId, String orgName, String status,
                                 boolean chargesEnabled, boolean payoutsEnabled, boolean payoutsPaused,
                                 String holdReason, boolean listingsHidden, boolean deauthorized, int openItems,
                                 Instant currentDeadline, String disabledReason, int remindersSent,
                                 Instant lastReminderAt, Map<String, String> capabilities,
                                 List<RequirementItem> requirements, List<HistoryView> history) {

        static AccountHealthResponse of(AccountHealthView v) {
            ConnectAccount a = v.account();
            var r = a.requirements();
            return new AccountHealthResponse(a.accountId(), a.orgId(), v.orgName(), a.status().name(),
                    r.chargesEnabled(), r.payoutsEnabled(), a.payoutsPaused(),
                    a.holdReason() == null ? null : a.holdReason().name(), a.listingsHidden(), a.deauthorized(),
                    r.open().size(), r.currentDeadline(), r.disabledReason(), a.remindersSent(), a.lastReminderAt(),
                    r.capabilities(), v.requirements(), v.history().stream().map(HistoryView::of).toList());
        }
    }
}
