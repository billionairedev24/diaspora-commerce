package com.afrotransact.seller.application;

/**
 * Settles open plan invoices in a fixed order: platform credit first (coupons are already off the invoice), then the
 * seller's earnings if — and only if — they chose to pay plans from sales, then the saved card or bank account with a
 * plain Stripe PaymentIntent. A failed charge is retried on a schedule (1, 3, 5 and 7 days later); when the last
 * attempt fails the invoice is {@code uncollectible} and the subscription {@code UNPAID}.
 */
public interface InvoiceCollectionService {

    /** What one pass did to the invoice. */
    enum Outcome {
        /** Settled in full (or already). */
        PAID,
        /** Waiting: a bank debit in flight, or earnings are being collected. */
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

    /** A PaymentIntent of a plan invoice ended (Stripe webhook): applies or fails the pending bank debit. */
    void onPaymentIntentResult(String paymentIntentId, boolean succeeded, String failureCode, String failureMessage);

    /** Staff waive: the rest of an open invoice is forgiven with a reason (audited by the caller's grant). */
    void waive(String invoiceId, String reasonCode, String note, String actor);
}
