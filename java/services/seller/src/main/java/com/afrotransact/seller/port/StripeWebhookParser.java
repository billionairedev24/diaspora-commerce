package com.afrotransact.seller.port;

/** Verifies a webhook's {@code Stripe-Signature} against the endpoint secret and parses the event. */
public interface StripeWebhookParser {

    enum Endpoint { CONNECT, BILLING }

    /** Throws {@code SellerRuleViolation} with code {@code seller.invalid-signature} when verification fails. */
    StripeEvent parse(Endpoint endpoint, String payload, String signatureHeader);
}
