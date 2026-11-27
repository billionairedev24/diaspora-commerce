package com.afrotransact.seller.port;

/** Verifies a webhook's {@code Stripe-Signature} against the endpoint secret and parses the event. */
public interface StripeWebhookParser {

    /** The seller module's only Stripe webhook is Connect (/v1/webhooks/stripe-connect); the name is kept in the inbox. */
    enum Endpoint { CONNECT }

    /** Throws {@code SellerRuleViolation} with code {@code seller.invalid-signature} when verification fails. */
    StripeEvent parse(Endpoint endpoint, String payload, String signatureHeader);
}
