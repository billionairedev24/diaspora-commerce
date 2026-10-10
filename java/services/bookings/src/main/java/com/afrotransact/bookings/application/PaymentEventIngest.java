package com.afrotransact.bookings.application;

import java.util.UUID;

/**
 * Consumes payments-service events (topic {@code at.payment.events}, key = order id = booking reference) and moves
 * bookings on: {@code payments.intent.succeeded} (deposit authorised, or — with {@code parentPaymentId} — the
 * off-session balance charge authorised), {@code payments.intent.failed} and, for balance charges,
 * {@code payments.intent.action_required}. Each event is applied at most once (inbox de-duplication on event id).
 */
public interface PaymentEventIngest {

    /** Consumer name in the inbox. */
    String CONSUMER = "bookings.payment-events";

    /**
     * Applies one event.
     *
     * @return true when the event changed a booking
     */
    boolean handle(UUID eventId, String eventType, IntentEvent event);

    /** Event of the booking's original intent (no parent payment). */
    boolean handle(UUID eventId, String eventType, String orderId, String paymentStatus, String failureMessage);

    /**
     * The parts of a {@code payments.intent.*} payload bookings uses.
     *
     * @param orderId         payments' order id (booking references start with {@code BK-}; other orders are ignored)
     * @param paymentId       the payment the event is about
     * @param parentPaymentId set for an off-session follow-up charge (the balance of a long-dated booking)
     * @param offSession      true for an off-session follow-up charge
     */
    record IntentEvent(String orderId, UUID paymentId, String status, String failureMessage, UUID parentPaymentId,
                       boolean offSession) {

        public boolean balanceCharge() {
            return parentPaymentId != null || offSession;
        }
    }
}
