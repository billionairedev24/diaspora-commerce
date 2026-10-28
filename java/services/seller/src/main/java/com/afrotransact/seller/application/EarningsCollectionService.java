package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.Invoice;

/**
 * Plan fees paid from earnings (S298), for organisations that chose {@code EARNINGS_FIRST} while the kill switch
 * {@code COLLECT_PLAN_FEES_FROM_EARNINGS} is on. It is the third step of {@link InvoiceCollectionService}: platform
 * credit has been used first, and the card pays whatever earnings did not cover.
 *
 * <p>The invoice is claimed once with the amount still owed after credit. payments is asked to withhold up to that
 * from the organisation's earnings (it may withhold less: only delivered, undisputed earnings count) and the amount it
 * withheld is recorded as an {@code EARNINGS} payment of the invoice. Every remote step is idempotent
 * ({@code plan-fee-<invoice>} at payments), so a retry never withholds twice; while a step fails for a transient reason
 * the collection retries with back-off and the card is not charged yet. A voided invoice releases what was withheld
 * back to the seller's next transfer.
 */
public interface EarningsCollectionService {

    /** What happened to one collection step. */
    enum Outcome {
        /** Nothing (more) to do now: the invoice is covered, declined or given up; the card pays the rest. */
        DONE,
        /** A transient failure: retry later, do not charge the card yet. */
        RETRY
    }

    /** Claims an open invoice for collection from earnings for {@code amountCents}; false when already claimed. */
    boolean request(Invoice invoice, long amountCents);

    /** Withholds and records one claimed invoice now. */
    Outcome collect(String invoiceId);

    /** A voided invoice: queues the release of what was withheld. */
    String onInvoiceVoided(String orgId, String invoiceId);

    /** Sends the queued releases of one invoice now; failures are left to the job. */
    void release(String invoiceId);

    /** Job: queued releases that are due. Returns how many were sent. */
    int processReleases();

    /** The kill switch. */
    boolean enabled();
}
