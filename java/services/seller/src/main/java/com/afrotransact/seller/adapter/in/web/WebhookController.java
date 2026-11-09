package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.seller.application.StripeWebhookService;
import com.afrotransact.seller.application.WebhookResult;
import com.afrotransact.seller.port.StripeWebhookParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stripe webhooks", description = "Signature-verified (Stripe-Signature) and de-duplicated by event id")
@RestController
class WebhookController {

    private final StripeWebhookService webhooks;

    WebhookController(StripeWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "Connect events: account.updated, capability.updated, person.created/updated, "
            + "account.external_account.created/updated/deleted, payout.failed, account.application.deauthorized")
    @PostMapping("/v1/webhooks/stripe-connect")
    WebhookResult stripeConnectWebhook(@RequestBody String payload,
                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        return webhooks.receive(StripeWebhookParser.Endpoint.CONNECT, payload, signature);
    }

    @Operation(summary = "Plan-fee PaymentIntent results: payment_intent.succeeded / payment_intent.payment_failed "
            + "(metadata purpose=plan_fee)",
            description = "Stripe Billing is removed (our own invoices, PaymentIntent collection). This endpoint remains "
                    + "only for plan-fee PaymentIntents that were still processing when charged (a bank debit settles "
                    + "days later, S298): it settles the invoice with the outcome. Any other event type, and a "
                    + "PaymentIntent without purpose=plan_fee, is acknowledged and ignored. Secret "
                    + "STRIPE_BILLING_WEBHOOK_SECRET; see docs/runbooks/stripe-webhooks.md.")
    @ApiResponse(responseCode = "200", description = "Applied (or a duplicate)")
    @ApiResponse(responseCode = "503", description = "Retry later: settling the invoice failed for a transient reason")
    @PostMapping("/v1/webhooks/stripe-billing")
    ResponseEntity<WebhookResult> stripeBillingWebhook(@RequestBody String payload,
                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        WebhookResult r = webhooks.receive(StripeWebhookParser.Endpoint.BILLING, payload, signature);
        return ResponseEntity.status(r.retryLater() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).body(r);
    }
}
