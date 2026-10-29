package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;

/** Plan invoices AfroTransact issues, with the payments that settle them. */
public interface InvoiceRepository {

    /** Inserts a new invoice; false when the organisation already has a cycle invoice for the same period start. */
    boolean insert(Invoice invoice);

    /** The cycle / create invoice of the organisation for the period starting at {@code periodStart}. */
    Optional<Invoice> findForPeriod(String orgId, Instant periodStart);

    List<Invoice> list(String orgId, int limit);

    /** One invoice of the organisation. */
    Optional<Invoice> find(String orgId, String invoiceId);

    Optional<Invoice> findById(String invoiceId);

    /** Locks the invoice row for the rest of the transaction. */
    Optional<Invoice> lock(String invoiceId);

    /** Ids of open invoices whose next collection attempt is due, oldest first. */
    List<String> dueForCollection(Instant now, int limit);

    /** Records a payment; false when the same (invoice, source, reference) was recorded before. */
    boolean addPayment(InvoicePayment payment);

    /** Moves a payment to APPLIED, FAILED or REVERSED. */
    void setPaymentStatus(UUID paymentId, String status, String note);

    /** The pending bank debit with this reference (a PaymentIntent id), if any. */
    Optional<InvoicePayment> findPaymentByReference(String reference);

    /** Recomputes {@code amount_paid_cents} from the applied payments. */
    void refreshPaid(String invoiceId, Instant at);

    /** open → paid. */
    void markPaid(String invoiceId, Instant at);

    /** open → void or uncollectible. */
    void markStatus(String invoiceId, String status, Instant at);

    /** uncollectible / open → open with a fresh set of attempts (the seller fixed their payment method). */
    void reopen(String invoiceId, Instant at);

    /** A failed collection attempt: counts it and schedules the next one (null = none left). */
    void recordAttempt(String invoiceId, int attempts, Instant nextAttemptAt, String error, Instant at);

    void setWaiver(String invoiceId, String reasonCode, String note, String actor, Instant at);

    /** The next number of the human-readable invoice series. */
    long nextNumber();
}
