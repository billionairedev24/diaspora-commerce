package com.afrotransact.bookings.application;

import java.util.UUID;

/**
 * Consumes payments-service events (topic {@code at.payment.events}, key = order id = booking reference) and moves
 * bookings on: {@code payments.intent.succeeded} (deposit authorised) and {@code payments.intent.failed}. Each
 * event is applied at most once (inbox de-duplication on event id).
 */
public interface PaymentEventIngest {

    /** Consumer name in the inbox. */
    String CONSUMER = "bookings.payment-events";

    /**
     * Applies one event.
     *
     * @param orderId payments' order id (booking references start with {@code BK-}; other orders are ignored)
     * @return true when the event changed a booking
     */
    boolean handle(UUID eventId, String eventType, String orderId, String paymentStatus, String failureMessage);
}
