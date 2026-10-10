package com.afrotransact.orders.application;

import java.util.UUID;

/** Consumes payments-service intent events and signals the matching checkout saga. */
public interface PaymentEventService {

    /**
     * Handles payments.intent.succeeded / failed / canceled once per event id.
     *
     * @return true when a checkout saga was signalled
     */
    boolean onIntentEvent(UUID eventId, String type, String orderId, String failureMessage);
}
