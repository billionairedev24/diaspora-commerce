package com.afrotransact.seller.adapter.in.web;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.adapter.in.web.BillingDtos.BillingResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.InvoiceResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.PlanAssignmentResponse;
import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.CollectionResponse;
import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.CollectionView;
import com.afrotransact.seller.adapter.in.web.RewardDtos.ChargeGateBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.CreditBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.DaysBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.GrantView;
import com.afrotransact.seller.adapter.in.web.RewardDtos.MonthsBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.WaiveBody;
import com.afrotransact.seller.application.BillingAdjustmentService;
import com.afrotransact.seller.application.BillingPanel;
import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.domain.SellerRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Billing adjustments", description = "Staff per-seller billing panel and audited adjustments (credits, "
        + "free days and months, charge conditions, waived invoices, trial overrides) with reason codes; billing:adjust to change. The "
        + "Idempotency-Key header makes a retried request return the first grant.")
@RestController
class BillingAdminController {

    private final BillingService billing;
    private final BillingAdjustmentService adjustments;

    BillingAdminController(BillingService billing, BillingAdjustmentService adjustments) {
        this.billing = billing;
        this.adjustments = adjustments;
    }

    @Schema(name = "BillingPanel", description = "Staff per-seller billing panel; billing is null before the "
            + "organisation has a subscription")
    record PanelResponse(String orgId, String orgName, String kind, String orgStatus, BillingResponse billing,
                         List<InvoiceResponse> invoices, List<GrantView> grants, List<CollectionView> collections,
                         boolean earningsCollectionEnabled,
                         @Schema(description = "How the plan is paid (S298); null before the subscription exists")
                         CollectionResponse planFeeCollection,
                         @Schema(description = "Plans staff assigned to the organisation, newest first (S306)")
                         List<PlanAssignmentResponse> planAssignments) {

        static PanelResponse of(BillingPanel p) {
            return new PanelResponse(p.orgId(), p.orgName(), p.kind().name(), p.orgStatus(),
                    p.overview() == null ? null : BillingResponse.of(p.overview()),
                    p.invoices().stream().map(InvoiceResponse::of).toList(),
                    p.grants().stream().map(GrantView::of).toList(),
                    p.collections().stream().map(CollectionView::of).toList(), p.earningsCollectionEnabled(),
                    p.planFeeCollection() == null ? null : CollectionResponse.of(p.planFeeCollection()),
                    p.planAssignments().stream().map(PlanAssignmentResponse::of).toList());
        }
    }

    @Operation(summary = "Per-seller billing panel (staff)")
    @GetMapping("/v1/orgs/{orgId}/billing/panel")
    PanelResponse billingPanel(@PathVariable String orgId) {
        return PanelResponse.of(billing.panel(orgId));
    }

    @Operation(summary = "Give platform credit the next invoices use first (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/credits")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView creditBalance(@PathVariable String orgId, @RequestBody CreditBody body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.credit(orgId, required(body.amountCents(), "amountCents"),
                body.reasonCode(), body.note(), key));
    }

    @Operation(summary = "Give free days: the first charge moves out (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/free-days")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView giveFreeDays(@PathVariable String orgId, @RequestBody DaysBody body,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.freeDays(orgId, required(body.days(), "days"), body.reasonCode(), body.note(),
                key));
    }

    @Operation(summary = "Give free calendar months: the first charge moves out (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/free-months")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView giveFreeMonths(@PathVariable String orgId, @RequestBody MonthsBody body,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.freeMonths(orgId, required(body.months(), "months"), body.reasonCode(),
                body.note(), key));
    }

    @Operation(summary = "Put a condition on the plan charges (billing:adjust)",
            description = "START_AFTER: no charge until the seller reaches the threshold (sales in cents, orders, "
                    + "bookings or live listings); WAIVE_WHILE: free while they keep it; WAIVE_UNTIL: free until a "
                    + "date. Replaces any earlier condition; a reason is required and shown in the grants log.")
    @PutMapping("/v1/orgs/{orgId}/billing/charge-gate")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView setChargeGate(@PathVariable String orgId, @RequestBody ChargeGateBody body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.setChargeGate(orgId, new BillingAdjustmentService.GateInput(body.mode(),
                body.metric(), body.threshold(), body.until()), body.reasonCode(), body.note(), key));
    }

    @Operation(summary = "Remove the condition on the plan charges: they apply as normal again (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/charge-gate/clear")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView clearChargeGate(@PathVariable String orgId, @RequestBody WaiveBody body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.clearChargeGate(orgId, body.reasonCode(), body.note(), key));
    }

    @Operation(summary = "Waive the rest of an open invoice (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/invoices/{invoiceId}/waive")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView waiveInvoice(@PathVariable String orgId, @PathVariable String invoiceId, @RequestBody WaiveBody body,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.waiveInvoice(orgId, invoiceId, body.reasonCode(), body.note(), key));
    }

    @Operation(summary = "Override the trial length for this organisation (billing:adjust)",
            description = "Before activation: the subscription starts with this trial. While trialing: the trial ends "
                    + "this many days after it started.")
    @PostMapping("/v1/orgs/{orgId}/billing/trial")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView overrideTrial(@PathVariable String orgId, @RequestBody DaysBody body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.setTrial(orgId, required(body.days(), "days"), body.reasonCode(),
                body.note(), key));
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new SellerRuleViolation("seller.invalid-adjustment", field + " is required",
                    Map.of(field, "Required"));
        }
        return value;
    }
}
