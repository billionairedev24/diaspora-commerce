package com.afrotransact.seller.adapter.in.web;

import java.util.List;

import com.afrotransact.seller.adapter.in.web.BillingDtos.AdjustmentsResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.DisputeResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.PaymentConfirmationResponse;
import com.afrotransact.seller.adapter.in.web.BillingDtos.RefundRequest;
import com.afrotransact.seller.adapter.in.web.BillingDtos.RefundResponse;
import com.afrotransact.seller.application.PlanFeePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Plan-fee payments", description = "After the charge: confirming a charge that needs 3-D Secure, refunds "
        + "(credits on the invoice) and disputes of plan fees. Stripe's outcomes (bank debits that settle in up to 4 "
        + "business days, late returns, refunds, disputes) arrive on the platform webhook and reach seller as "
        + "payments.plan_fee.stripe_event.")
@RestController
class PlanFeePaymentController {

    private final PlanFeePaymentService planFees;

    PlanFeePaymentController(PlanFeePaymentService planFees) {
        this.planFees = planFees;
    }

    @Operation(summary = "Confirm a plan charge your bank asks you to approve (owner / finance)",
            description = "For an invoice with actionRequired: returns the PaymentIntent's client secret and payment "
                    + "method for Stripe.js confirmCardPayment. 409 seller.no-action-needed when nothing waits.")
    @PostMapping("/v1/orgs/{orgId}/billing/invoices/{invoiceId}/confirm-payment")
    PaymentConfirmationResponse confirmPayment(@PathVariable String orgId, @PathVariable String invoiceId) {
        return PaymentConfirmationResponse.of(planFees.confirmPayment(orgId, invoiceId));
    }

    @Operation(summary = "Refunds and disputes of an invoice (owner / finance, or staff)")
    @GetMapping("/v1/orgs/{orgId}/billing/invoices/{invoiceId}/adjustments")
    AdjustmentsResponse adjustments(@PathVariable String orgId, @PathVariable String invoiceId) {
        return AdjustmentsResponse.of(planFees.adjustments(orgId, invoiceId));
    }

    @Operation(summary = "Refund a plan fee, full or partial (billing:adjust)",
            description = "Refunds through Stripe (idempotency key from the Idempotency-Key header, so a retry returns "
                    + "the first refund) and records a credit on the invoice; audited with the staff member, reason "
                    + "code and note. 422 seller.invalid-refund (amount, reason), 409 seller.nothing-to-refund.")
    @PostMapping("/v1/orgs/{orgId}/billing/invoices/{invoiceId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    RefundResponse refund(@PathVariable String orgId, @PathVariable String invoiceId,
                          @Valid @RequestBody RefundRequest body,
                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return RefundResponse.of(planFees.refund(orgId, invoiceId, body.amountCents(), body.reasonCode(), body.note(),
                key));
    }

    @Operation(summary = "Plan-fee disputes (staff: seller:billing, billing:adjust or plans:manage)",
            description = "outcome OPEN (default view), WON or LOST; empty for all. Newest first, at most 200.")
    @GetMapping("/v1/billing/disputes")
    List<DisputeResponse> disputes(@RequestParam(required = false) String outcome) {
        return planFees.disputes(outcome).stream().map(DisputeResponse::of).toList();
    }
}
