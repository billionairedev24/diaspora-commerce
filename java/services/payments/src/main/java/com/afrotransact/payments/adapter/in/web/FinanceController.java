package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.BalanceResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ConnectedAccountRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ConnectedAccountResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.EffectiveFeesResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.FeeScheduleRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.FeeScheduleResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.JournalResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.OverrideRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ReconcileRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.ReconciliationResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.SplitResponse;
import com.afrotransact.payments.application.ConnectedAccountService;
import com.afrotransact.payments.application.FeeScheduleService;
import com.afrotransact.payments.application.LedgerService;
import com.afrotransact.payments.application.NewFeeScheduleCommand;
import com.afrotransact.payments.application.ReconciliationService;
import com.afrotransact.payments.application.SplitService;
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

    FinanceController(SplitService splits, FeeScheduleService fees, LedgerService ledger,
                      ReconciliationService reconciliation, ConnectedAccountService accounts) {
        this.splits = splits;
        this.fees = fees;
        this.ledger = ledger;
        this.reconciliation = reconciliation;
        this.accounts = accounts;
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

    @Operation(summary = "Effective fees for an organisation and category (payments:service or payments:read)",
            description = "Commission (seller override, then category, then default), card-fee estimate "
                    + "(cardPctBps + cardFixedMinor per charge) and FBA fee of the version in force at `at` (default "
                    + "now). AfroTransact Retail gets zero commission and zero FBA fee (source RETAIL): only its card "
                    + "fee share applies. Callers such as bookings use it instead of hard-coding fees. "
                    + "Fee schedules are per currency; there is no market dimension.")
    @GetMapping("/v1/fee-schedules/effective")
    EffectiveFeesResponse effectiveFees(@RequestParam String category,
                                        @RequestParam(required = false) String orgId,
                                        @RequestParam(required = false) Instant at) {
        return EffectiveFeesResponse.of(fees.effective(category, orgId, at));
    }

    @Operation(summary = "Create a fee schedule version (Super Admin / Finance, payments:configure)",
            description = "Applies to orders placed from effectiveFrom (never in the past); history is kept.")
    @PostMapping("/v1/fee-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    FeeScheduleResponse createFeeSchedule(@Valid @RequestBody FeeScheduleRequest body) {
        return FeeScheduleResponse.of(fees.create(new NewFeeScheduleCommand(body.effectiveFrom(), body.currency(),
                body.categoryBps(), body.defaultCommissionBps(), body.overrides() == null ? List.of()
                : body.overrides().stream().map(OverrideRequest::toOverride).toList(), body.cardPctBps(),
                body.cardFixedMinor(), body.fbaPerUnitMinor(), !Boolean.FALSE.equals(body.reverseCommissionOnRefund()), body.note())));
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
