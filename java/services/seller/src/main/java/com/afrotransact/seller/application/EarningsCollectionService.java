package com.afrotransact.seller.application;

import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeEvent;

/**
 * Plan fees paid from earnings (S298) for organisations that chose {@code EARNINGS_FIRST}, while the kill switch
 * {@code COLLECT_PLAN_FEES_FROM_EARNINGS} is on.
 *
 * <p>Timing: Stripe creates a renewal (or trial-end) invoice as a draft and finalises it one hour after our endpoint
 * acknowledged {@code invoice.created} (up to 72 hours while the endpoint keeps failing). The webhook claims the
 * invoice; right after the webhook transaction commits the seller service asks payments to withhold up to the
 * invoice amount (less the customer's credit balance) from the organisation's earnings and credits the Stripe customer
 * balance by what was withheld, so the invoice is finalised with the card charged only for the rest. While a step
 * fails for a transient reason the webhook answers 503, so Stripe redelivers and keeps the invoice a draft; a job
 * retries with back-off as well. Before the credit the invoice is read again: once Stripe finalised it the withholding
 * is released instead (the card paid). Every remote step is idempotent ({@code plan-fee-<invoice>} at payments,
 * {@code earnings-<invoice>} at Stripe), so a retry never withholds or credits twice.
 *
 * <p>A voided or deleted invoice (and a credit note refunded out of band) releases the withholding back to the
 * seller's next transfer; when the credit had reached the invoice it is taken back from the customer balance first.
 */
public interface EarningsCollectionService {

    /** What happened to one collection step. */
    enum Outcome {
        /** Nothing (more) to do now. */
        DONE,
        /** A transient failure: retry (the webhook answers 503 so Stripe keeps the invoice a draft). */
        RETRY
    }

    /** invoice.created (inside the webhook transaction): claims a renewal invoice of an EARNINGS_FIRST organisation. */
    String onInvoiceCreated(String orgId, InvoiceSnapshot invoice);

    /** invoice.voided / invoice.deleted (inside the webhook transaction): queues the release of what was withheld. */
    String onInvoiceVoided(String orgId, InvoiceSnapshot invoice);

    /** credit_note.created (inside the webhook transaction): releases the part refunded out of band. */
    String onCreditNote(String orgId, StripeEvent.CreditNote creditNote);

    /** Withholds and credits one claimed invoice now (after the webhook committed). */
    Outcome collect(String invoiceId);

    /** Sends the queued releases of one invoice now (after the webhook committed); failures are left to the job. */
    void release(String invoiceId);

    /** Job: due collections and releases. Returns how many invoices were credited. */
    int processOpen();

    /** The kill switch. */
    boolean enabled();
}
