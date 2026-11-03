package com.afrotransact.seller.application;

/**
 * Outcome of a webhook delivery: {@code duplicate} when the event id was processed before; {@code retryLater} when a
 * step that must happen before Stripe finalises an invoice failed for a transient reason (the endpoint answers 503,
 * so Stripe redelivers and keeps the invoice a draft).
 */
public record WebhookResult(String eventId, String type, boolean duplicate, String outcome, boolean retryLater) {

    public WebhookResult(String eventId, String type, boolean duplicate, String outcome) {
        this(eventId, type, duplicate, outcome, false);
    }

    WebhookResult retry() {
        return new WebhookResult(eventId, type, duplicate, outcome, true);
    }
}
