package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.adapter.in.web.BillingDtos.BillingResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.InvoiceResponse;
import com.afrotransact.seller.adapter.in.web.RewardDtos.CreditBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.DaysBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.EarningsBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.GrantView;
import com.afrotransact.seller.adapter.in.web.RewardDtos.WaiveBody;
import com.afrotransact.seller.application.BillingAdjustmentService;
import com.afrotransact.seller.application.BillingPanel;
import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.EarningsCollectionService;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.EarningsCollectionRepository;
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
        + "free days, waived invoices, trial overrides) with reason codes; billing:adjust to change. The "
        + "Idempotency-Key header makes a retried request return the first grant.")
@RestController
class BillingAdminController {

    private final BillingService billing;
    private final BillingAdjustmentService adjustments;
    private final EarningsCollectionService earnings;

    BillingAdminController(BillingService billing, BillingAdjustmentService adjustments,
                           EarningsCollectionService earnings) {
        this.billing = billing;
        this.adjustments = adjustments;
        this.earnings = earnings;
    }

    @Schema(name = "EarningsCollection")
    record CollectionView(String invoiceId, long amountCents,
                          @Schema(allowableValues = {"REQUESTED", "WITHHELD", "CREDITED", "DECLINED", "FAILED"})
                          String status, String paymentsReference, String stripeBalanceTransactionId, String reason,
                          Instant createdAt) {

        static CollectionView of(EarningsCollectionRepository.Row r) {
            return new CollectionView(r.invoiceId(), r.amountCents(), r.status(), r.paymentsReference(),
                    r.balanceTransactionId(), r.reason(), r.createdAt());
        }
    }

    @Schema(name = "BillingPanel", description = "Staff per-seller billing panel; billing is null before the "
            + "organisation has a subscription")
    record PanelResponse(String orgId, String orgName, String kind, String orgStatus, BillingResponse billing,
                         List<InvoiceResponse> invoices, List<GrantView> grants, List<CollectionView> collections,
                         boolean earningsCollectionEnabled) {

        static PanelResponse of(BillingPanel p) {
            return new PanelResponse(p.orgId(), p.orgName(), p.kind().name(), p.orgStatus(),
                    p.overview() == null ? null : BillingResponse.of(p.overview()),
                    p.invoices().stream().map(InvoiceResponse::of).toList(),
                    p.grants().stream().map(GrantView::of).toList(),
                    p.collections().stream().map(CollectionView::of).toList(), p.earningsCollectionEnabled());
        }
    }

    @Operation(summary = "Per-seller billing panel (staff)")
    @GetMapping("/v1/orgs/{orgId}/billing/panel")
    PanelResponse billingPanel(@PathVariable String orgId) {
        return PanelResponse.of(billing.panel(orgId));
    }

    @Operation(summary = "Credit the organisation's Stripe balance (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/credits")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView creditBalance(@PathVariable String orgId, @RequestBody CreditBody body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.credit(orgId, required(body.amountCents(), "amountCents"),
                body.reasonCode(), body.note(), key));
    }

    @Operation(summary = "Give free days: the subscription's trial_end moves out (billing:adjust)")
    @PostMapping("/v1/orgs/{orgId}/billing/free-days")
    @ResponseStatus(HttpStatus.CREATED)
    GrantView giveFreeDays(@PathVariable String orgId, @RequestBody DaysBody body,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return GrantView.of(adjustments.freeDays(orgId, required(body.days(), "days"), body.reasonCode(), body.note(),
                key));
    }

    @Operation(summary = "Waive (void) an open invoice (billing:adjust)")
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

    @Operation(summary = "Opt the organisation in or out of collecting plan fees from earnings (billing:adjust)",
            description = "409 seller.earnings-collection-off while the platform flag is off.")
    @PutMapping("/v1/orgs/{orgId}/billing/earnings-collection")
    PanelResponse setEarningsCollection(@PathVariable String orgId, @RequestBody EarningsBody body) {
        earnings.setOptIn(orgId, body.enabled());
        return PanelResponse.of(billing.panel(orgId));
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new SellerRuleViolation("seller.invalid-adjustment", field + " is required",
                    Map.of(field, "Required"));
        }
        return value;
    }
}
