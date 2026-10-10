package com.afrotransact.payments.adapter.out.stripe;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.payments.port.StripeWebhookVerifier;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Verifies {@code Stripe-Signature} with {@link Webhook#constructEvent} against the endpoint secret. */
@Component
class StripeSignatureVerifier implements StripeWebhookVerifier {

    private final String secret;
    private final long toleranceSeconds;
    private final Clock clock;

    StripeSignatureVerifier(@Value("${at.payments.stripe.webhook-secret}") String secret,
                            @Value("${at.payments.stripe.webhook-tolerance-seconds:300}") long toleranceSeconds,
                            Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET is not set");
        }
        this.secret = secret;
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    @Override
    public VerifiedEvent verify(String payload, String signatureHeader) {
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookSignatureException("Missing Stripe-Signature header or body", null);
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, secret, toleranceSeconds, clock);
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookSignatureException("Invalid Stripe signature: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new InvalidWebhookSignatureException("Unreadable Stripe event", e);
        }
        if (event.getId() == null || event.getType() == null) {
            throw new InvalidWebhookSignatureException("Stripe event without id or type", null);
        }
        Instant created = event.getCreated() == null ? Instant.now(clock) : Instant.ofEpochSecond(event.getCreated());
        return new VerifiedEvent(event.getId(), event.getType(), event.getAccount(), created,
                Boolean.TRUE.equals(event.getLivemode()), payload);
    }
}
