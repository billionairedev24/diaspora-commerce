package com.afrotransact.payments.adapter.in.web;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.WebhookAck;
import com.afrotransact.payments.application.WebhookReceipt;
import com.afrotransact.payments.application.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stripe webhooks", description = "Signed, idempotent inbox for Stripe events")
@RestController
class WebhookController {

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "Stripe webhook endpoint",
            description = "Verifies Stripe-Signature with the endpoint secret (400 payments.invalid-signature), "
                    + "stores the event once by id and acknowledges; processing is asynchronous. Duplicates and "
                    + "unknown types are acknowledged.")
    @SecurityRequirements
    @PostMapping(path = "/v1/webhooks/stripe", consumes = MediaType.ALL_VALUE)
    WebhookAck receive(@RequestHeader(value = "Stripe-Signature", required = false) String signature,
                       @RequestBody String payload) {
        WebhookReceipt r = webhooks.receive(payload, signature);
        return new WebhookAck(true, r.eventId(), r.type(), r.duplicate(), r.handled());
    }
}
