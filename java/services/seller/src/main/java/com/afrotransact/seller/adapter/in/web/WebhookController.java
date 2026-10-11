package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.seller.application.StripeWebhookService;
import com.afrotransact.seller.application.WebhookResult;
import com.afrotransact.seller.port.StripeWebhookParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "Billing events: invoice.paid, invoice.payment_failed, customer.subscription.updated/deleted")
    @PostMapping("/v1/webhooks/stripe-billing")
    WebhookResult stripeBillingWebhook(@RequestBody String payload,
                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        return webhooks.receive(StripeWebhookParser.Endpoint.BILLING, payload, signature);
    }
}
