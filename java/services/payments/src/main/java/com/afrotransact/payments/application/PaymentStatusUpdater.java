package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentStatus;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/** Applies a Stripe-reported intent status (webhook or status read) and publishes the matching event once. */
@Component
class PaymentStatusUpdater {

    private final PaymentRepository payments;
    private final DomainEventPublisher events;
    private final Clock clock;

    PaymentStatusUpdater(PaymentRepository payments, DomainEventPublisher events, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    /** Call with the payment locked; returns the stored payment. */
    Payment apply(Payment locked, PaymentStatus reported, String failure) {
        Payment next = locked.syncStatus(reported, failure, Instant.now(clock));
        if (next.status() == locked.status() && Objects.equals(next.authorizedAt(), locked.authorizedAt())) {
            return locked;
        }
        Payment stored = payments.update(next);
        if (locked.authorizedAt() == null && stored.authorizedAt() != null) {
            publish(PaymentsEvents.INTENT_SUCCEEDED, stored);
        } else if (stored.status() == PaymentStatus.FAILED && locked.status() != PaymentStatus.FAILED) {
            publish(PaymentsEvents.INTENT_FAILED, stored);
        } else if (stored.status() == PaymentStatus.CANCELED && locked.status() != PaymentStatus.CANCELED) {
            publish(PaymentsEvents.INTENT_CANCELED, stored);
        }
        return stored;
    }

    /**
     * An off-session charge waiting for the customer to authenticate: Stripe leaves the intent in
     * {@code requires_payment_method} after {@code authentication_required}; it is not a decline, so the payment stays
     * REQUIRES_ACTION until the customer confirms on-session (or the caller cancels it).
     */
    boolean awaitingCustomer(Payment p, String stripeStatus) {
        return p.isOffSession() && p.status() == PaymentStatus.REQUIRES_ACTION
                && ("requires_payment_method".equals(stripeStatus) || "requires_action".equals(stripeStatus)
                || "requires_confirmation".equals(stripeStatus));
    }

    void publish(String type, Payment p) {
        events.publish(type, PaymentsEvents.AGGREGATE_PAYMENT, p.orderId(),
                PaymentsEventPayloads.IntentPayload.of(p));
    }
}
