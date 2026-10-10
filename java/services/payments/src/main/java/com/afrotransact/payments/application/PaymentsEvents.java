package com.afrotransact.payments.application;

/** Event types published by payments-service (contracts/asyncapi/fragments/payments.yaml). */
public final class PaymentsEvents {

    /** Payment, capture, refund, transfer and dispute events: topic at.payment.events, key = order id. */
    public static final String AGGREGATE_PAYMENT = "payment";
    /** Payout events: topic at.payout.events, key = organisation id. */
    public static final String AGGREGATE_PAYOUT = "payout";

    public static final String INTENT_CREATED = "payments.intent.created";
    /** Funds authorised (requires_capture) — the order can be confirmed. */
    public static final String INTENT_SUCCEEDED = "payments.intent.succeeded";
    public static final String INTENT_FAILED = "payments.intent.failed";
    public static final String INTENT_CANCELED = "payments.intent.canceled";
    public static final String CAPTURED = "payments.captured";
    public static final String REFUNDED = "payments.refunded";
    public static final String TRANSFER_CREATED = "payments.transfer.created";
    public static final String PAYOUT_REQUESTED = "payments.payout.requested";
    public static final String PAYOUT_APPROVED = "payments.payout.approved";
    public static final String PAYOUT_PAID = "payments.payout.paid";
    public static final String PAYOUT_FAILED = "payments.payout.failed";
    public static final String DISPUTE_OPENED = "payments.dispute.opened";
    public static final String DISPUTE_CLOSED = "payments.dispute.closed";

    private PaymentsEvents() {
    }
}
