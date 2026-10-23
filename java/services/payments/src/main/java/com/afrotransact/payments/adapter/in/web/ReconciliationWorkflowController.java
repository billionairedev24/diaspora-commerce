package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.JournalResponse;
import com.afrotransact.payments.application.LedgerAdjustmentService;
import com.afrotransact.payments.application.LedgerAdjustmentService.Line;
import com.afrotransact.payments.application.LedgerAdjustmentService.ProposeAdjustment;
import com.afrotransact.payments.application.ReconciliationWorkflowService;
import com.afrotransact.payments.application.ReconciliationWorkflowService.ItemDetail;
import com.afrotransact.payments.application.StripeDashboard;
import com.afrotransact.payments.application.StripeLookupService;
import com.afrotransact.payments.application.StripeLookupService.StripeLookup;
import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.EntrySide;
import com.afrotransact.payments.domain.JournalLine;
import com.afrotransact.payments.domain.LedgerAdjustment;
import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;
import com.afrotransact.payments.port.StripeGateway.ConnectedBalanceSnapshot;
import com.afrotransact.payments.port.StripeGateway.ObjectSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reconciliation", description = "Differences between the ledger and Stripe, Stripe lookups and ledger "
        + "adjustments with four-eyes approval (staff)")
@RestController
class ReconciliationWorkflowController {

    private final ReconciliationWorkflowService workflow;
    private final LedgerAdjustmentService adjustments;
    private final StripeLookupService lookup;
    private final StripeDashboard dashboard;

    ReconciliationWorkflowController(ReconciliationWorkflowService workflow, LedgerAdjustmentService adjustments,
                                     StripeLookupService lookup, StripeDashboard dashboard) {
        this.dashboard = dashboard;
        this.workflow = workflow;
        this.adjustments = adjustments;
        this.lookup = lookup;
    }

    // ------------------------------------------------------------------ DTOs

    @Schema(name = "ReconciliationItem")
    record ItemResponse(UUID id, UUID runId, Instant windowStart, String stripeRef, String currency, long ledgerMinor,
                        long stripeMinor,
                        @Schema(description = "Ledger minus Stripe; positive: the ledger holds more than Stripe")
                        long differenceMinor,
                        @Schema(allowableValues = {"OPEN", "EXPLAINED", "ADJUSTED", "SUPERSEDED"}) String status,
                        String note, String resolvedBy, Instant resolvedAt, Instant createdAt) {

        static ItemResponse of(ReconciliationItem i) {
            return new ItemResponse(i.id(), i.runId(), i.windowStart(), i.stripeRef(), i.currency(), i.ledgerMinor(),
                    i.stripeMinor(), i.differenceMinor(), i.status().name(), i.note(), i.resolvedBy(), i.resolvedAt(),
                    i.createdAt());
        }
    }

    @Schema(name = "AdjustmentLine")
    record AdjustmentLineResponse(String account, String title, String side, long amountMinor, String currency,
                                  String orgId) {

        static AdjustmentLineResponse of(JournalLine l) {
            return new AdjustmentLineResponse(l.account().display(), l.account().chart().title(), l.side().name(),
                    l.amount().minor(), l.amount().currency(), l.orgId());
        }
    }

    @Schema(name = "LedgerAdjustment")
    record AdjustmentResponse(UUID id, UUID itemId, String stripeRef, String currency, String reason,
                              List<AdjustmentLineResponse> lines,
                              @Schema(allowableValues = {"PROPOSED", "POSTED", "REJECTED"}) String status,
                              String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt,
                              String decisionNote, UUID journalId) {

        static AdjustmentResponse of(LedgerAdjustment a) {
            return new AdjustmentResponse(a.id(), a.itemId(), a.stripeRef(), a.currency(), a.reason(),
                    a.lines().stream().map(AdjustmentLineResponse::of).toList(), a.status().name(), a.proposedBy(),
                    a.proposedAt(), a.decidedBy(), a.decidedAt(), a.decisionNote(), a.journalId());
        }
    }

    @Schema(name = "StripeObject")
    record StripeObjectResponse(String id, String kind, String status, long amountMinor, String currency,
                                Instant created, String description, String failure, Map<String, String> links) {

        static StripeObjectResponse of(ObjectSnapshot o) {
            return o == null ? null : new StripeObjectResponse(o.id(), o.kind(), o.status(), o.amountMinor(),
                    o.currency(), o.created(), o.description(), o.failure(), o.links());
        }
    }

    @Schema(name = "StripeConnectedBalance")
    record ConnectedBalanceResponse(String account, String currency, long availableMinor, long pendingMinor) {

        static ConnectedBalanceResponse of(ConnectedBalanceSnapshot b) {
            return b == null ? null : new ConnectedBalanceResponse(b.account(), b.currency(), b.availableMinor(),
                    b.pendingMinor());
        }
    }

    @Schema(name = "StripeLookup")
    record LookupResponse(String ref,
                          @Schema(description = "Stripe's view; null when Stripe has no such object or could not "
                                  + "be asked (see problem)") StripeObjectResponse object,
                          String problem, List<JournalResponse> journals, String connectedAccount,
                          ConnectedBalanceResponse balance,
                          @Schema(description = "Open the object in the Stripe dashboard; null for the local stand-in")
                          String dashboardUrl) {

        static LookupResponse of(StripeLookup l, StripeDashboard dashboard) {
            return new LookupResponse(l.ref(), StripeObjectResponse.of(l.object()), l.problem(),
                    l.journals().stream().map(JournalResponse::of).toList(), l.connectedAccount(),
                    ConnectedBalanceResponse.of(l.balance()), dashboard.url(l.ref()));
        }
    }

    @Schema(name = "ReconciliationItemDetail")
    record ItemDetailResponse(ItemResponse item, LookupResponse stripe, List<JournalResponse> journals,
                              List<AdjustmentResponse> adjustments) {

        static ItemDetailResponse of(ItemDetail d, StripeDashboard dashboard) {
            return new ItemDetailResponse(ItemResponse.of(d.item()), LookupResponse.of(d.stripe(), dashboard),
                    d.journals().stream().map(JournalResponse::of).toList(),
                    d.adjustments().stream().map(AdjustmentResponse::of).toList());
        }
    }

    record ExplainRequest(@NotBlank String note) {
    }

    record DecisionRequest(String note) {
    }

    @Schema(name = "AdjustmentLineRequest")
    record LineRequest(@NotBlank @Schema(description = "Chart account code, e.g. 1000, 2000, 5000",
                               examples = "2000") String account,
                       @Schema(description = "Sub-account key for scoped accounts: the organisation id for 2000, "
                               + "the jurisdiction for 2100; empty otherwise") String key,
                       @NotNull EntrySide side,
                       @Positive long amountMinor,
                       @Schema(description = "Seller the line is attributed to, for statements") String orgId) {
    }

    record ProposeRequest(@Schema(description = "The reconciliation difference this corrects; its Stripe object is "
            + "used for the journal") UUID itemId,
                          @Schema(description = "Stripe object the journal is booked against; required when a line "
                                  + "is on 1000 Stripe clearing and there is no itemId") String stripeRef,
                          @NotBlank String reason,
                          @NotEmpty @Valid List<LineRequest> lines) {
    }

    // ------------------------------------------------------------------ endpoints

    @Operation(summary = "Stripe lookup: one Stripe object next to what the ledger booked for it (payments:read)",
            description = "ref is a Stripe id (po_, tr_, pi_, ch_, re_, txn_, dp_, acct_). A payout lives on the "
                    + "seller's connected account, so pass orgId. When Stripe has no such object, or cannot be "
                    + "asked, object is null and problem says why; the ledger's journals are still returned.")
    @GetMapping("/v1/stripe/lookup")
    LookupResponse lookup(@RequestParam String ref, @RequestParam(required = false) String orgId) {
        return LookupResponse.of(lookup.lookup(ref, orgId), dashboard);
    }

    @Operation(summary = "Reconciliation differences, newest first (payments:read)")
    @GetMapping("/v1/reconciliation/items")
    List<ItemResponse> items(@RequestParam(required = false) ReconciliationStatus status,
                             @RequestParam(defaultValue = "100") int limit) {
        return workflow.items(status, limit).stream().map(ItemResponse::of).toList();
    }

    @Operation(summary = "One difference with Stripe's view, the ledger's journals and its adjustments "
            + "(payments:read)")
    @GetMapping("/v1/reconciliation/items/{id}")
    ItemDetailResponse item(@PathVariable UUID id) {
        return ItemDetailResponse.of(workflow.item(id), dashboard);
    }

    @Operation(summary = "Mark an open difference explained, posting nothing (ledger:adjust)",
            description = "For differences that are not errors (timing, a known fee). The note is kept; a later run "
                    + "of the same day that finds the same difference keeps it explained.")
    @PostMapping("/v1/reconciliation/items/{id}/explain")
    ItemResponse explain(@PathVariable UUID id, @Valid @RequestBody ExplainRequest body) {
        return ItemResponse.of(workflow.explain(id, body.note()));
    }

    @Operation(summary = "Ledger adjustments, newest first (payments:read)")
    @GetMapping("/v1/ledger/adjustments")
    List<AdjustmentResponse> adjustments(@RequestParam(required = false) AdjustmentStatus status,
                                         @RequestParam(defaultValue = "100") int limit) {
        return adjustments.list(status, limit).stream().map(AdjustmentResponse::of).toList();
    }

    @Operation(summary = "One ledger adjustment (payments:read)")
    @GetMapping("/v1/ledger/adjustments/{id}")
    AdjustmentResponse adjustment(@PathVariable UUID id) {
        return AdjustmentResponse.of(adjustments.get(id));
    }

    @Operation(summary = "Propose a correcting journal (ledger:adjust)",
            description = "Nothing is posted until a different person with ledger:approve approves it. Lines must "
                    + "balance, in the ledger's currency. 422 payments.adjustment-pending while another proposal "
                    + "for the same difference waits.")
    @PostMapping("/v1/ledger/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    AdjustmentResponse proposeAdjustment(@Valid @RequestBody ProposeRequest body) {
        List<Line> lines = body.lines().stream()
                .map(l -> new Line(l.account(), l.key(), l.side(), l.amountMinor(), l.orgId())).toList();
        return AdjustmentResponse.of(adjustments.propose(
                new ProposeAdjustment(body.itemId(), body.stripeRef(), body.reason(), lines)));
    }

    @Operation(summary = "Approve and post a proposed adjustment (ledger:approve, not the proposer)",
            description = "403 payments.adjustment-four-eyes when the approver proposed it.")
    @PostMapping("/v1/ledger/adjustments/{id}/approve")
    AdjustmentResponse approveAdjustment(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest body) {
        return AdjustmentResponse.of(adjustments.approve(id, body == null ? null : body.note()));
    }

    @Operation(summary = "Reject a proposed adjustment with the reason (ledger:approve; the proposer may withdraw)")
    @PostMapping("/v1/ledger/adjustments/{id}/reject")
    AdjustmentResponse rejectAdjustment(@PathVariable UUID id, @RequestBody DecisionRequest body) {
        return AdjustmentResponse.of(adjustments.reject(id, body == null ? null : body.note()));
    }
}
