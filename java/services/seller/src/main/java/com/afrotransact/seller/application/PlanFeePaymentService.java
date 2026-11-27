package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.PlanFeeDispute;
import com.afrotransact.seller.domain.PlanFeeRefund;

/**
 * Plan-fee payments after the charge: Stripe's events (bank debits that settle days later, 3-D Secure confirmations,
 * late returns, refunds, disputes) handed over by payments, the seller's on-session confirmation of a charge that needs
 * 3-D Secure, and staff refunds and dispute views.
 */
public interface PlanFeePaymentService {

    /**
     * Applies one plan-fee Stripe event (consumer of payments.plan_fee.stripe_event), de-duplicated on the Stripe event
     * id in the same transaction. Returns the outcome stored with the event ({@code duplicate} for a replay).
     */
    String apply(PlanFeeStripeEvent event);

    /**
     * The seller (owner or finance) confirms a charge that needs 3-D Secure: the PaymentIntent's client secret and payment
     * method for Stripe.js {@code confirmCardPayment}. The invoice settles when Stripe's event arrives.
     */
    PaymentConfirmation confirmPayment(String orgId, String invoiceId);

    /**
     * Staff refund (billing:adjust) of a paid plan fee, full or partial, with a reason code (OTHER needs a note); goes
     * through Stripe with an idempotency key (the request's Idempotency-Key, so a retry returns the first refund) and is
     * recorded as a credit on the invoice.
     */
    PlanFeeRefund refund(String orgId, String invoiceId, long amountCents, String reasonCode, String note,
                         String requestKey);

    /** Refunds and disputes of one invoice (the organisation's owner or finance, or staff with seller:billing). */
    InvoiceAdjustments adjustments(String orgId, String invoiceId);

    /** Plan-fee disputes for staff (OPEN, WON, LOST or null for all), newest first. */
    List<PlanFeeDispute> disputes(String outcome);

    /** What the browser needs to confirm a charge; {@code status} is the PaymentIntent's status as we map it. */
    record PaymentConfirmation(String invoiceId, String paymentIntentId, String clientSecret, String paymentMethodId,
                               String status) {
    }

    /** Refunds (credits on the invoice) and disputes of one invoice. */
    record InvoiceAdjustments(String invoiceId, long refundedCents, List<PlanFeeRefund> refunds,
                              List<PlanFeeDispute> disputes) {
    }
}
