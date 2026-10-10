package com.afrotransact.payments.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.Refund;

/**
 * Order payment lifecycle, called by the orders saga (SERVICE principals, Idempotency-Key on every write):
 * create PaymentIntent (manual capture) → capture per shipment → refunds per line, or cancel.
 */
public interface PaymentService {

    /** Creates the order's PaymentIntent and returns its client secret. Idempotent per order id. */
    PaymentResult create(CreatePaymentCommand command, String idempotencyKey);

    /** @param refresh read the intent's status from Stripe first (confirmation / 3-D Secure) */
    Payment get(UUID paymentId, boolean refresh);

    Payment getByOrder(String orderId);

    /** Captures one shipment and posts its journal. Idempotent per (payment, shipment). */
    CaptureResult capture(UUID paymentId, CaptureCommand command, String idempotencyKey);

    /** Cancels an uncaptured intent (releases the authorisation). Idempotent. */
    Payment cancel(UUID paymentId, String reason, String idempotencyKey);

    /**
     * Releases the uncaptured rest of the authorisation (after a deposit capture, or all of it without captures).
     * Idempotent per Idempotency-Key and per payment (a released payment replays).
     */
    ReleaseResult release(UUID paymentId, String reason, String idempotencyKey);

    /** Off-session follow-up charges of a payment, oldest first. */
    List<Payment> offSessionCharges(UUID paymentId);

    /** Refunds captured lines (partially), reversing seller payables, commission and transfers. */
    RefundResult refund(UUID paymentId, RefundCommand command, String idempotencyKey);

    List<Capture> captures(UUID paymentId);

    List<Refund> refunds(UUID paymentId);
}
