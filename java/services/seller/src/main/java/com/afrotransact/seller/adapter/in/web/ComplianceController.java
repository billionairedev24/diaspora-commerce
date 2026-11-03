package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.seller.adapter.in.web.OnboardingDtos.AccountSessionResponse;
import com.afrotransact.seller.application.AccountHealthView;
import com.afrotransact.seller.application.ComplianceService;
import com.afrotransact.seller.application.RequirementItem;
import com.afrotransact.seller.application.StripeOnboardingService;
import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.PayoutBank;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.AccountSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stripe compliance", description = "Account health for sellers (owner / finance) and the staff "
        + "compliance console (compliance:remind, compliance:hold). Stripe is the source of truth and collects "
        + "every answer in its embedded components; Account health is read-only.")
@RestController
class ComplianceController {

    private final ComplianceService compliance;
    private final StripeOnboardingService stripe;

    ComplianceController(ComplianceService compliance, StripeOnboardingService stripe) {
        this.compliance = compliance;
        this.stripe = stripe;
    }

    @Operation(summary = "Account health: what Stripe needs, and the embedded component that collects each item")
    @GetMapping("/v1/orgs/{orgId}/account-health")
    AccountHealthResponse accountHealth(@PathVariable String orgId) {
        return AccountHealthResponse.of(compliance.accountHealth(orgId));
    }

    @Operation(summary = "Account Session for Stripe's embedded components (owner / finance)",
            description = "Seller accounts are Stripe-collected: what Stripe needs (identity, tax ID, documents, "
                    + "terms) and the payout bank account are entered in Stripe's embedded components — account "
                    + "onboarding, account management, notification banner — never through AfroTransact. Returns "
                    + "client_secret for Connect.js. Staff can't open one.")
    @PostMapping("/v1/orgs/{orgId}/stripe/account-session")
    AccountSessionResponse orgAccountSession(@PathVariable String orgId) {
        AccountSession s = stripe.accountSession(orgId);
        return new AccountSessionResponse(s.accountId(), s.clientSecret(), s.expiresAt(), s.localTest());
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
                                 Instant lastReminderAt, Map<String, String> capabilities, PayoutBank payoutBank,
                                 List<RequirementItem> requirements, List<HistoryView> history) {

        static AccountHealthResponse of(AccountHealthView v) {
            ConnectAccount a = v.account();
            var r = a.requirements();
            return new AccountHealthResponse(a.accountId(), a.orgId(), v.orgName(), a.status().name(),
                    r.chargesEnabled(), r.payoutsEnabled(), a.payoutsPaused(),
                    a.holdReason() == null ? null : a.holdReason().name(), a.listingsHidden(), a.deauthorized(),
                    r.open().size(), r.currentDeadline(), r.disabledReason(), a.remindersSent(), a.lastReminderAt(),
                    r.capabilities(), r.payoutBank(), v.requirements(), v.history().stream().map(HistoryView::of).toList());
        }
    }
}
