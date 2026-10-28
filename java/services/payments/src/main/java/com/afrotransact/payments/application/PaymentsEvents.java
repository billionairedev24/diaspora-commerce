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
    /** An off-session charge needs the customer to authenticate (SCA / 3-D Secure) on-session. */
    public static final String INTENT_ACTION_REQUIRED = "payments.intent.action_required";
    /** The uncaptured rest of an authorisation was released after a partial (deposit) capture. */
    public static final String AUTHORIZATION_RELEASED = "payments.authorization.released";
    public static final String CAPTURED = "payments.captured";
    public static final String REFUNDED = "payments.refunded";
    public static final String TRANSFER_CREATED = "payments.transfer.created";
    public static final String PAYOUT_REQUESTED = "payments.payout.requested";
    public static final String PAYOUT_APPROVED = "payments.payout.approved";
    public static final String PAYOUT_PAID = "payments.payout.paid";
    public static final String PAYOUT_FAILED = "payments.payout.failed";
    public static final String DISPUTE_OPENED = "payments.dispute.opened";
    public static final String DISPUTE_CLOSED = "payments.dispute.closed";
    /** A provider fee (late provider cancellation) was recorded: topic at.payout.events, key = organisation. */
    public static final String PROVIDER_FEE_RECORDED = "payments.provider_fee.recorded";
    /** A provider fee was debited from the provider's connected account. */
    public static final String PROVIDER_FEE_COLLECTED = "payments.provider_fee.collected";

    /**
     * An AfroTransact Retail supplier payment was booked and scheduled (retail.payment.released, S226): topic
     * at.payout.events, key = supplier id.
     */
    public static final String SUPPLIER_PAYMENT_SCHEDULED = "payments.supplier_payment.scheduled";
    /** Finance paid a supplier payment from the operating bank account (key = supplier id). */
    public static final String SUPPLIER_PAYMENT_PAID = "payments.supplier_payment.paid";
    /** Finance put a supplier payment on hold or released it (status ON_HOLD / SCHEDULED, key = supplier id). */
    public static final String SUPPLIER_PAYMENT_UPDATED = "payments.supplier_payment.updated";

    /** The gift card email with its code is due (topic at.payment.events, key = gift card id), S321. */
    public static final String GIFT_CARD_DELIVER = "payments.gift_card.deliver";
    /** Audit events of gift cards (key = gift card id): issued by staff, redeemed, voided, resent. */
    public static final String GIFT_CARD_ISSUED = "payments.gift_card.issued";
    public static final String GIFT_CARD_REDEEMED = "payments.gift_card.redeemed";
    public static final String GIFT_CARD_VOIDED = "payments.gift_card.voided";
    public static final String GIFT_CARD_RESENT = "payments.gift_card.resent";
    /** A gift card was paid for and is active (key = gift card id). */
    public static final String GIFT_CARD_PURCHASED = "payments.gift_card.purchased";
    /** A staff grant or correction of credit was posted (key = customer id), S320. */
    public static final String CREDIT_ADJUSTED = "payments.credit.adjusted";
    /** A shopper's membership started, renewed, failed, lapsed, ended, changed or got free days (key = customer id). */
    public static final String MEMBERSHIP_CHANGED = "payments.membership.changed";
    /** Staff changed a membership tier or the billing rules, with the reason (audit; key = tier code). */
    public static final String MEMBERSHIP_TIER_CHANGED = "payments.membership.tier_changed";

    private PaymentsEvents() {
    }
}
