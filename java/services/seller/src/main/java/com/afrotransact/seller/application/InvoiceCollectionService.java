package com.afrotransact.seller.application;

/**
 * Settles open plan invoices in a fixed order: platform credit first (coupons are already off the invoice), then the
 * seller's earnings if — and only if — they chose to pay plans from sales, then the saved card with a plain Stripe
 * PaymentIntent (card only: plan fees are never debited from a bank account). A failed charge is retried on a schedule
 * (1, 3, 5 and 7 days later); when the last attempt fails the invoice is {@code uncollectible} and the subscription
 * {@code UNPAID}. A card charge Stripe leaves {@code processing} stays pending on the open invoice and the next pass asks
 * Stripe how it ended (there is no plan-fee webhook).
 */
public interface InvoiceCollectionService {

    /** What one pass did to the invoice. */
    enum Outcome {
        /** Settled in full (or already). */
        PAID,
        /** Waiting: a card charge Stripe is still processing, or earnings are being collected. */
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

    /** Staff waive: the rest of an open invoice is forgiven with a reason (audited by the caller's grant). */
    void waive(String invoiceId, String reasonCode, String note, String actor);
}
