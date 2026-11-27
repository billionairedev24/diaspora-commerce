package com.afrotransact.seller.application;

/**
 * Settles open plan invoices in a fixed order: platform credit first (coupons are already off the invoice), then the
 * seller's earnings if — and only if — they chose to pay plans from sales, then the saved card or US bank account
 * (ACH) with a plain Stripe PaymentIntent. A failed charge is retried on a schedule (1, 3, 5 and 7 days later); when the
 * last attempt fails the invoice is {@code uncollectible} and the subscription {@code UNPAID}.
 *
 * <p>A bank debit is {@code processing} for up to 4 business days and a charge may need 3-D Secure (ACTION_REQUIRED: the
 * seller confirms it in Plan & billing). Their outcome arrives on the platform webhook: payments hands plan-fee events
 * to seller (payments.plan_fee.stripe_event), which calls the {@code on…} methods below. A late bank-debit return or a
 * lost dispute takes a settled payment back: the invoice reopens and dunning restarts.
 */
public interface InvoiceCollectionService {

    /** What one pass did to the invoice. */
    enum Outcome {
        /** Settled in full (or already). */
        PAID,
        /** Waiting: a bank debit in flight, a charge the seller must confirm, or earnings being collected. */
        PENDING,
        /** A charge failed or the payment system was unavailable; a retry is scheduled. */
        RETRY,
        /** Every attempt failed: the invoice is uncollectible. */
        FAILED
    }

    /** Settles what it can of one open invoice now. Safe to call repeatedly and from several replicas. */
    Outcome collect(String invoiceId);

    /** Job: collects open invoices whose next attempt is due. Returns how many were paid. */
    int collectDue();

    /**
     * A plan-fee PaymentIntent succeeded or failed (Stripe event). Succeeded settles a pending or action-required
     * payment; failed counts a failed attempt, or, for a payment that had already settled (a late bank-debit return),
     * reopens the invoice and restarts dunning. Idempotent. False when no plan payment has this PaymentIntent.
     */
    boolean onPaymentIntentResult(String paymentIntentId, boolean succeeded, String failureCode, String failureMessage);

    /** payment_intent.processing: a charge the seller just confirmed is in flight again. False when unknown. */
    boolean onProcessing(String paymentIntentId);

    /** payment_intent.requires_action: the payment waits for the seller (3-D Secure); owners are told. */
    boolean onActionRequired(String paymentIntentId);

    /** A settled payment was taken back (a lost dispute): the invoice reopens and dunning restarts. */
    boolean onPaymentTakenBack(String paymentIntentId, String code, String message);

    /** Staff waive: the rest of an open invoice is forgiven with a reason (audited by the caller's grant). */
    void waive(String invoiceId, String reasonCode, String note, String actor);
}
