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

    @Operation(summary = "Billing events: invoice.created/finalized/paid/payment_failed/voided/deleted, "
            + "credit_note.created, customer.subscription.created/updated/deleted",
            description = "invoice.created claims a renewal invoice of an organisation that pays its plan from its "
                    + "sales and collects it at once (S298). 503 while that collection failed for a transient reason: "
                    + "Stripe redelivers and keeps the invoice a draft until we acknowledge it.")
    @ApiResponse(responseCode = "200", description = "Applied (or a duplicate)")
    @ApiResponse(responseCode = "503", description = "Retry later: paying the invoice from sales is still pending")
    @PostMapping("/v1/webhooks/stripe-billing")
    ResponseEntity<WebhookResult> stripeBillingWebhook(@RequestBody String payload,
                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        WebhookResult r = webhooks.receive(StripeWebhookParser.Endpoint.BILLING, payload, signature);
        return ResponseEntity.status(r.retryLater() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).body(r);
    }
}
