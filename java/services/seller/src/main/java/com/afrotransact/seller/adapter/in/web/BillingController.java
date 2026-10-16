package com.afrotransact.seller.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.adapter.in.web.BillingDtos.AddPaymentMethodRequest;
import com.afrotransact.seller.adapter.in.web.BillingDtos.BillingResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.ChangePlanRequest;
import com.afrotransact.seller.adapter.in.web.BillingDtos.ChangePlanResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.InvoiceResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.OrgEntitlementsResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.PaymentMethodResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.PlanChangePreviewResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.PlanResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.SetupIntentResponse;
import com.afrotransact.seller.application.AddPaymentMethodCommand;
import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.EntitlementsService;
import com.afrotransact.seller.application.PlanChangeResult;
import com.afrotransact.seller.application.PlanService;
import com.afrotransact.seller.application.SetupIntentView;
import com.afrotransact.seller.domain.Invoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Plans & billing", description = "Plan catalogue (public) and each organisation's subscription, "
        + "invoices and payment methods. Owner and finance can view; only the owner changes billing.")
@RestController
class BillingController {

    private final PlanService plans;
    private final BillingService billing;
    private final EntitlementsService entitlements;

    BillingController(PlanService plans, BillingService billing, EntitlementsService entitlements) {
        this.plans = plans;
        this.billing = billing;
        this.entitlements = entitlements;
    }

    @Operation(summary = "What the organisation's plan allows: live-listing cap, AI listing writer, Lola tier",
            description = "Readable by every member of the organisation, by staff with a billing permission and by "
                    + "services with sellers:entitlements.read (offers-service checks the listing cap with it). An "
                    + "organisation without a plan (AfroTransact Retail) is not capped.")
    @GetMapping("/v1/orgs/{orgId}/entitlements")
    OrgEntitlementsResponse orgEntitlements(@PathVariable String orgId) {
        return OrgEntitlementsResponse.of(entitlements.forOrg(orgId));
    }

    @Operation(summary = "Plans with monthly, quarterly (5% off) and annual (2 months free) prices (public)")
    @GetMapping("/v1/plans")
    List<PlanResponse> listPlans(@RequestParam(required = false) String kind) {
        return plans.list(kind == null ? null : Inputs.kind(kind)).stream().map(PlanResponse::of).toList();
    }

    @Operation(summary = "Plans this organisation may choose (public, invited, and its current plan) (owner / finance)")
    @GetMapping("/v1/orgs/{orgId}/billing/plans")
    List<PlanResponse> listOrgPlans(@PathVariable String orgId) {
        return plans.listFor(orgId).stream().map(PlanResponse::of).toList();
    }

    @Operation(summary = "Billing page: plan, next charge or trial end, pending change, payment methods")
    @GetMapping("/v1/orgs/{orgId}/billing")
    BillingResponse billingOverview(@PathVariable String orgId) {
        return BillingResponse.of(billing.overview(orgId));
    }

    @Operation(summary = "Change plan or cadence (owner)",
            description = "Upgrades on the same cadence prorate immediately; downgrades and cadence changes are "
                    + "recorded as a pending change applied at the next renewal.")
    @PostMapping("/v1/orgs/{orgId}/billing/plan")
    ChangePlanResponse changePlan(@PathVariable String orgId, @Valid @RequestBody ChangePlanRequest body) {
        PlanChangeResult r = billing.changePlan(orgId, body.planCode(),
                body.cadence() == null ? null : Inputs.cadence(body.cadence()));
        return new ChangePlanResponse(r.applied().name(), BillingResponse.of(r.billing()));
    }

    @Operation(summary = "Cancel at period end (owner)")
    @PostMapping("/v1/orgs/{orgId}/billing/cancel")
    BillingResponse cancelSubscription(@PathVariable String orgId) {
        return BillingResponse.of(billing.cancelAtPeriodEnd(orgId));
    }

    @Operation(summary = "Resume a subscription set to cancel (owner)")
    @PostMapping("/v1/orgs/{orgId}/billing/resume")
    BillingResponse resumeSubscription(@PathVariable String orgId) {
        return BillingResponse.of(billing.resume(orgId));
    }

    @Operation(summary = "Payment methods")
    @GetMapping("/v1/orgs/{orgId}/billing/payment-methods")
    List<PaymentMethodResponse> listPaymentMethods(@PathVariable String orgId) {
        return billing.paymentMethods(orgId).stream().map(PaymentMethodResponse::of).toList();
    }

    @Operation(summary = "Add a card or bank account (owner)")
    @PostMapping("/v1/orgs/{orgId}/billing/payment-methods")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentMethodResponse addPaymentMethod(@PathVariable String orgId, @Valid @RequestBody AddPaymentMethodRequest b) {
        return PaymentMethodResponse.of(billing.addPaymentMethod(orgId, new AddPaymentMethodCommand(
                b.stripePaymentMethodId(), Inputs.paymentMethodKind(b.kind()), b.brand(), b.last4(), b.expMonth(),
                b.expYear(), b.bankName(), b.accountType(), Boolean.TRUE.equals(b.makeDefault()))));
    }

    @Operation(summary = "Make a payment method the default (owner)")
    @PostMapping("/v1/orgs/{orgId}/billing/payment-methods/{id}/default")
    List<PaymentMethodResponse> makeDefaultPaymentMethod(@PathVariable String orgId, @PathVariable UUID id) {
        return billing.makeDefault(orgId, id).stream().map(PaymentMethodResponse::of).toList();
    }

    @Operation(summary = "Remove a payment method (owner); 409 for the default method")
    @DeleteMapping("/v1/orgs/{orgId}/billing/payment-methods/{id}")
    List<PaymentMethodResponse> removePaymentMethod(@PathVariable String orgId, @PathVariable UUID id) {
        return billing.removePaymentMethod(orgId, id).stream().map(PaymentMethodResponse::of).toList();
    }

    @Operation(summary = "Invoices with hosted page and PDF links")
    @GetMapping("/v1/orgs/{orgId}/billing/invoices")
    List<InvoiceResponse> listInvoices(@PathVariable String orgId) {
        return billing.invoices(orgId).stream().map(InvoiceResponse::of).toList();
    }

    @Operation(summary = "A printable invoice (owner / finance)",
            description = "Self-contained HTML; the browser's Print › Save as PDF makes the PDF. 404 "
                    + "seller.invoice-not-found.")
    @GetMapping(value = "/v1/orgs/{orgId}/billing/invoices/{invoiceId}/document", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> invoiceDocument(@PathVariable String orgId, @PathVariable String invoiceId) {
        Invoice invoice = billing.invoice(orgId, invoiceId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(InvoiceDocument.render(invoice,
                billing.billedTo(orgId)));
    }

    @Operation(summary = "Retry an unpaid invoice now (owner / finance)",
            description = "After fixing the payment method: reopens an uncollectible invoice and collects it again "
                    + "(credit, then earnings if chosen, then the saved card or bank).")
    @PostMapping("/v1/orgs/{orgId}/billing/invoices/{invoiceId}/retry")
    InvoiceResponse retryInvoice(@PathVariable String orgId, @PathVariable String invoiceId) {
        return InvoiceResponse.of(billing.retryInvoice(orgId, invoiceId));
    }

    @Operation(summary = "Start adding a card or bank account (owner): Stripe SetupIntent",
            description = "Confirm the clientSecret with Stripe.js (Payment Element) or the mobile PaymentSheet, then "
                    + "register the resulting pm_… with POST /billing/payment-methods.")
    @PostMapping("/v1/orgs/{orgId}/billing/setup-intents")
    @ResponseStatus(HttpStatus.CREATED)
    SetupIntentResponse createSetupIntent(@PathVariable String orgId) {
        SetupIntentView v = billing.setupIntent(orgId);
        return new SetupIntentResponse(v.customerId(), v.setupIntentId(), v.clientSecret());
    }

    @Operation(summary = "Preview a plan or cadence change (owner / finance)",
            description = "What POST /billing/plan would do: IMMEDIATE_UPGRADE (amountDueNowCents = the proration, 0 during a trial), AT_RENEWAL (effectiveAt = renewal) or NO_CHANGE. Nothing changes.")
    @GetMapping("/v1/orgs/{orgId}/billing/plan/preview")
    PlanChangePreviewResponse previewPlanChange(@PathVariable String orgId, @RequestParam String planCode,
                                                @RequestParam(required = false) String cadence) {
        return PlanChangePreviewResponse.of(billing.previewPlanChange(orgId, planCode,
                cadence == null ? null : Inputs.cadence(cadence)));
    }
}
