package com.afrotransact.seller.application;

import com.afrotransact.seller.port.StripeWebhookParser;

/** Signature-verified, de-duplicated Stripe webhook intake for the Connect and Billing endpoints. */
public interface StripeWebhookService {

    WebhookResult receive(StripeWebhookParser.Endpoint endpoint, String payload, String signature);
}
