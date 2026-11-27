package com.afrotransact.seller.it;

import com.afrotransact.seller.application.PlanFeeStripeEvent;

/**
 * payments.plan_fee.stripe_event as payments publishes it after the platform webhook received a plan-fee Stripe event
 * (Kafka is off in the ITs, so the tests hand it to PlanFeePaymentService the way PlanFeeEventListener does).
 */
final class PlanFeeEvents {

    private PlanFeeEvents() {
    }

    static PlanFeeStripeEvent intent(String eventId, String type, String intentId, String invoiceId) {
        return failed(eventId, type, intentId, invoiceId, null, null);
    }

    static PlanFeeStripeEvent failed(String eventId, String type, String intentId, String invoiceId, String code,
                                     String message) {
        return new PlanFeeStripeEvent(eventId, type, System.currentTimeMillis() / 1000, "plan_fee", "payment_intent",
                intentId, invoiceId, null, null, 0, 0, 0, "usd", null, code, message, null, null, null, null, null,
                null, null);
    }

    static PlanFeeStripeEvent refunded(String eventId, String intentId, String chargeId, long cumulativeRefunded) {
        return new PlanFeeStripeEvent(eventId, "charge.refunded", System.currentTimeMillis() / 1000, "plan_fee",
                "charge", intentId, null, null, "succeeded", 0, 0, cumulativeRefunded, "usd", chargeId, null, null, null,
                null, null, null, null, null, null);
    }

    static PlanFeeStripeEvent dispute(String eventId, String type, String intentId, String disputeId, String status,
                                      long amount) {
        return new PlanFeeStripeEvent(eventId, type, System.currentTimeMillis() / 1000, "plan_fee", "dispute",
                intentId, null, null, null, amount, 0, 0, "usd", "ch_" + disputeId, null, null, null, disputeId, status,
                "fraudulent", null, null, null);
    }
}
