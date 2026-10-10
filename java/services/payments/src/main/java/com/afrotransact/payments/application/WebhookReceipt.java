package com.afrotransact.payments.application;

/**
 * Acknowledgement of a Stripe delivery.
 *
 * @param duplicate the event id was already stored (re-delivery); nothing else happens
 * @param handled   the type is processed; other types are stored and acknowledged only
 */
public record WebhookReceipt(String eventId, String type, boolean duplicate, boolean handled) {
}
