package com.afrotransact.seller.application;

/**
 * One Stripe event of a plan fee, as payments hands it over (payments.plan_fee.stripe_event on at.payment.events,
 * contracts/asyncapi/fragments/payments.yaml). Payments received it on the platform webhook; seller has no plan-fee
 * webhook of its own.
 */
public record PlanFeeStripeEvent(String stripeEventId, String stripeEventType, long created, String purpose,
                                 String objectType, String paymentIntentId, String invoiceId, String orgId,
                                 String status, long amountMinor, long amountReceivedMinor, long amountRefundedMinor,
                                 String currency, String chargeId, String failureCode, String failureMessage,
                                 String nextActionType, String disputeId, String disputeStatus, String disputeReason,
                                 String setupIntentId, String paymentMethodId, String mandateId) {
}
