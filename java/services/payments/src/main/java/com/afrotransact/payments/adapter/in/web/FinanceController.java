package com.afrotransact.payments.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.BalanceResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.CancelFeeScheduleRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ConnectedAccountRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ConnectedAccountResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.DeliveryQuoteRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.DeliveryQuoteResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.EffectiveFeesResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.FeeScheduleRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.FeeScheduleResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.JournalResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.OverrideRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.RateCardResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ReconcileRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ReconciliationResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.SplitResponse;
import com.afrotransact.payments.application.ConnectedAccountService;
import com.afrotransact.payments.application.FeeScheduleService;
import com.afrotransact.payments.application.LedgerService;
import com.afrotransact.payments.application.NewFeeScheduleCommand;
import com.afrotransact.payments.application.ReconciliationService;
import com.afrotransact.payments.application.SplitService;
import com.afrotransact.payments.domain.FeeKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Finance", description = "Staff views and configuration: payment split, fees & commissions, ledger, "
        + "reconciliation, connected accounts")
@RestController
class FinanceController {

    private final SplitService splits;
    private final FeeScheduleService fees;
    private final LedgerService ledger;
    private final ReconciliationService reconciliation;
    private final ConnectedAccountService accounts;
    private final Clock clock;

    FinanceController(SplitService splits, FeeScheduleService fees, LedgerService ledger,
                      ReconciliationService reconciliation, ConnectedAccountService accounts, Clock clock) {
        this.splits = splits;
        this.fees = fees;
        this.ledger = ledger;
        this.reconciliation = reconciliation;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Operation(summary = "Payment split of an order (staff, payments:read)",
            description = "Per recipient: items, shipping, tax collected, commission, FBA fees, pro-rata Stripe fee "
                    + "share (largest remainder, leftover cents to AfroTransact) and net. Totals always reconcile "
                    + "to the charge.")
    @GetMapping("/v1/orders/{orderId}/split")
    SplitResponse split(@PathVariable String orderId) {
        return SplitResponse.of(splits.splitOf(orderId));
    }

    @Operation(summary = "Fee schedule versions, newest first (payments:read)")
    @GetMapping("/v1/fee-schedules")
    List<FeeScheduleResponse> feeSchedules() {
        return fees.history().stream().map(FeeScheduleResponse::of).toList();
    }

    @Operation(summary = "Fee schedule in force at a time (default now)")
    @GetMapping("/v1/fee-schedules/active")
    FeeScheduleResponse activeFeeSchedule(@RequestParam(required = false) Instant at) {
        return FeeScheduleResponse.of(fees.activeAt(at));
    }

    @Operation(summary = "Effective fees for an organisation, kind and category (payments:service, "
            + "payments:fees.read or payments:read)",
            description = "The commission (kind PRODUCT, default) or connection fee (kind SERVICE: category = the "
                    + "service's category) of the version in force at `at` (default now), first match wins: an "
                    + "active negotiated rate of the organisation (its category, then '*'), the organisation's plan "
                    + "rate (category, then the plan's '*'), the category rate, the default. Plus the card-fee "
                    + "estimate (cardPctBps + cardFixedMinor per charge) and the FBA fee (products only). AfroTransact "
                    + "Retail pays none (source RETAIL): only its card fee share applies. Callers such as bookings "
                    + "use it instead of hard-coding fees. Fee schedules are per currency; there is no market "
                    + "dimension.")
    @GetMapping("/v1/fee-schedules/effective")
    EffectiveFeesResponse effectiveFees(@RequestParam String category,
                                        @RequestParam(required = false) String orgId,
                                        @RequestParam(required = false) Instant at,
                                        @RequestParam(required = false) FeeKind kind) {
        return EffectiveFeesResponse.of(fees.effective(kind == null ? FeeKind.PRODUCT : kind, category, orgId, at));
    }

    @Operation(summary = "Every commission or connection-fee rate of an organisation, with its source",
            description = "kind PRODUCT (default): product commission per category; SERVICE: connection fee per "
                    + "service category. Each rate says where it comes from (OVERRIDE = negotiated, PLAN, CATEGORY, "
                    + "DEFAULT, RETAIL), plus the rate of unlisted categories, the organisation's plan and its "
                    + "negotiated rates that are active or scheduled at `at` (default now). Staff fee readers and "
                    + "services (payments:read, payments:fees.read), or the organisation's members who see payouts.")
    @GetMapping("/v1/orgs/{orgId}/rate-card")
    RateCardResponse rateCard(@PathVariable String orgId, @RequestParam(required = false) FeeKind kind,
                              @RequestParam(required = false) Instant at) {
        return RateCardResponse.of(fees.rateCard(orgId, kind, at), Instant.now(clock));
    }

    @Operation(summary = "Create a fee schedule version (Super Admin / Finance, payments:configure)",
            description = "Applies to orders placed from effectiveFrom (never in the past); history is kept.")
    @PostMapping("/v1/fee-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    FeeScheduleResponse createFeeSchedule(@Valid @RequestBody FeeScheduleRequest body) {
        return FeeScheduleResponse.of(fees.create(new NewFeeScheduleCommand(body.effectiveFrom(), body.currency(),
                body.categoryBps(), body.defaultCommissionBps(), body.overrides() == null ? List.of()
                : body.overrides().stream().map(OverrideRequest::toOverride).toList(), body.cardPctBps(),
                body.cardFixedMinor(), body.fbaPerUnitMinor(), !Boolean.FALSE.equals(body.reverseCommissionOnRefund()),
                body.note(), body.deliveryPricing() == null ? null : body.deliveryPricing().toCommand(),
                body.planBps(), body.connectionFee() == null ? null : body.connectionFee().toTable(),
                body.planFbaMinor())));
    }

    @Operation(summary = "Price an AfroTransact Delivery trip from a seller's store (S255)",
            description = "Under the fee-schedule version in force at `at` (default now): the customer's delivery "
                    + "charge and the seller's pickup-delivery fee (seller override first; 0 for AfroTransact Retail, "
                    + "source RETAIL). `available=false` with BEYOND_MAX_RADIUS beyond the version's service radius "
                    + "(the offer is not deliverable to that address) or NOT_CONFIGURED when the version has no "
                    + "delivery pricing. FLAT tariffs count packages; DISTANCE tariffs price the trip. "
                    + "orders-service (payments:service), services with payments:fees.read, staff payments:read.")
    @PostMapping("/v1/delivery-pricing/quote")
    DeliveryQuoteResponse quoteDelivery(@Valid @RequestBody DeliveryQuoteRequest body) {
        return DeliveryQuoteResponse.of(fees.quoteDelivery(body.sellerOrgId(), body.distanceMiles(),
                body.packages() == null ? 1 : body.packages(), body.at()));
    }

    @Operation(summary = "Cancel a scheduled fee schedule version (Super Admin / Finance, payments:configure)",
            description = "Only before its effectiveFrom (409 payments.fee-schedule-in-force afterwards, 409 "
                    + "payments.fee-schedule-cancelled when already cancelled). Fee schedules stay immutable: the "
                    + "version is kept and marked cancelled (cancelledAt, cancelledBy, cancelReason), never deleted, "
                    + "and is skipped when picking the version in force. Audited.")
    @PostMapping("/v1/fee-schedules/{id}/cancel")
    FeeScheduleResponse cancelFeeSchedule(@PathVariable UUID id, @Valid @RequestBody CancelFeeScheduleRequest body) {
        return FeeScheduleResponse.of(fees.cancel(id, body.reason()));
    }

    @Operation(summary = "Ledger balances of every account (payments:read)",
            description = "AfroTransact Retail sales appear on their own revenue account 4400, separate from the "
                    + "2000:<org> seller payables (Retail is never a payable).")
    @GetMapping("/v1/ledger/balances")
    List<BalanceResponse> balances() {
        return ledger.balances().stream().map(BalanceResponse::of).toList();
    }

    @Operation(summary = "Ledger balances attributed to one seller / provider organisation (payments:read)")
    @GetMapping("/v1/ledger/orgs/{orgId}/balances")
    List<BalanceResponse> orgBalances(@PathVariable String orgId) {
        return ledger.balancesOfOrg(orgId).stream().map(BalanceResponse::of).toList();
    }

    @Operation(summary = "Journals of an order (payments:read)")
    @GetMapping("/v1/ledger/journals")
    List<JournalResponse> journals(@RequestParam String orderId) {
        return ledger.journalsOfOrder(orderId).stream().map(JournalResponse::of).toList();
    }

    @Operation(summary = "Recent reconciliation runs (payments:read)")
    @GetMapping("/v1/reconciliation/runs")
    List<ReconciliationResponse> runs(@RequestParam(defaultValue = "30") int limit) {
        return reconciliation.recent(limit).stream().map(ReconciliationResponse::of).toList();
    }

    @Operation(summary = "Reconcile one day now (payments:configure)",
            description = "Compares ledger 1000 Stripe clearing per Stripe object with Stripe balance transactions.")
    @PostMapping("/v1/reconciliation/runs")
    @ResponseStatus(HttpStatus.CREATED)
    ReconciliationResponse reconcile(@Valid @RequestBody ReconcileRequest body) {
        return ReconciliationResponse.of(reconciliation.reconcile(body.date()));
    }

    @Operation(summary = "Register the Stripe Connect account of an organisation (seller-service or Finance)")
    @PutMapping("/v1/connected-accounts/{orgId}")
    ConnectedAccountResponse registerAccount(@PathVariable String orgId,
                                             @Valid @RequestBody ConnectedAccountRequest body) {
        accounts.register(orgId, body.stripeAccountId());
        return new ConnectedAccountResponse(orgId, body.stripeAccountId());
    }

    @Operation(summary = "Stripe Connect account of an organisation")
    @GetMapping("/v1/connected-accounts/{orgId}")
    ConnectedAccountResponse account(@PathVariable String orgId) {
        return new ConnectedAccountResponse(orgId, accounts.accountOf(orgId));
    }
}
