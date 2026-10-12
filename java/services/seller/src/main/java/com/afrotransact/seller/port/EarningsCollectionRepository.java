package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plan invoices paid from earnings (S298): one collection per Stripe invoice, and the releases sent to payments when
 * such an invoice is voided or refunded.
 */
public interface EarningsCollectionRepository {

    /** Inserts once per invoice (REQUESTED); false when the invoice was already claimed. */
    boolean claim(String invoiceId, String orgId, String invoiceNumber, long invoiceAmountCents, long amountCents,
                  Instant at);

    Optional<Row> find(String invoiceId);

    /** SELECT … FOR UPDATE. */
    Optional<Row> lock(String invoiceId);

    /**
     * Moves the collection from {@code from} to {@code to} (with what changed); false when it is no longer in
     * {@code from} (a void overtook it, another worker finished it).
     */
    boolean transition(String invoiceId, String from, String to, Change change, Instant at);

    /** Counts a failed attempt and schedules the next one. */
    void failedAttempt(String invoiceId, String error, Instant nextAttemptAt, Instant at);

    /** Invoices whose REQUESTED / WITHHELD collection is due (again), oldest first. */
    List<String> dueInvoices(Instant now, int limit);

    List<Row> forOrg(String orgId, int limit);

    /** Collections of these invoices (the invoice list shows what was paid from sales). */
    Map<String, Row> forInvoices(Collection<String> invoiceIds);

    /** The organisation's collection still in progress (REQUESTED / WITHHELD), if any. */
    Optional<Row> inProgress(String orgId);

    /** Adds to released_cents (a release reached payments). */
    void addReleased(String invoiceId, long cents, String status, Instant at);

    /** Queues a release; false when one with this key exists already. */
    boolean requestRelease(String idempotencyKey, String invoiceId, long amountCents, String reason,
                           boolean debitBalance, Instant at);

    /** Keys of the PENDING releases due (again), oldest first; {@code invoiceId} narrows them to one invoice. */
    List<String> dueReleases(Instant now, String invoiceId, int limit);

    /** A PENDING release, locked (SELECT … FOR UPDATE SKIP LOCKED: empty when another worker has it). */
    Optional<Release> lockRelease(String idempotencyKey);

    void releaseDone(String idempotencyKey, String balanceTransactionId, Instant at);

    void releaseFailed(String idempotencyKey, String error, Instant nextAttemptAt, boolean giveUp, Instant at);

    /** Records the Stripe balance debit of a release (before payments is told, so a retry never debits twice). */
    void releaseDebited(String idempotencyKey, String balanceTransactionId, Instant at);

    /**
     * What a transition changes (null fields keep their value).
     *
     * @param withheldCents what payments withheld
     * @param creditedCents what was credited to the Stripe customer balance
     */
    record Change(Long withheldCents, Long creditedCents, String paymentsReference, String balanceTransactionId,
                  String reason) {

        public static Change reason(String reason) {
            return new Change(null, null, null, null, reason);
        }
    }

    /**
     * One invoice's collection.
     *
     * @param invoiceAmountCents the invoice's amount due when Stripe created it
     * @param amountCents        what payments was asked for (the invoice less the customer's credit balance)
     */
    record Row(String invoiceId, String orgId, String invoiceNumber, Long invoiceAmountCents, long amountCents,
               String status, long withheldCents, long creditedCents, long releasedCents, String paymentsReference,
               String balanceTransactionId, String reason, int attempts, String lastError, Instant nextAttemptAt,
               Instant createdAt, Instant updatedAt) {

        /** Paid from sales and not released: what the seller's earnings paid of the invoice. */
        public long fromSalesCents() {
            return Math.max(0, creditedCents - releasedCents);
        }
    }

    /** A release queued for payments (and the Stripe balance debit when the credit must be taken back). */
    record Release(String idempotencyKey, String invoiceId, String orgId, long amountCents, String reason,
                   boolean debitBalance, String balanceTransactionId, int attempts) {
    }
}
