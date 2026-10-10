package com.afrotransact.seller.application;

/** Outcome of a webhook delivery: {@code duplicate} when the event id was processed before. */
public record WebhookResult(String eventId, String type, boolean duplicate, String outcome) {
}
