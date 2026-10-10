package com.afrotransact.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment AfroTransact Retail owes a supplier for a purchase order whose invoices passed the three-way match
 * (retail-service {@code retail.payment.released}, S226). Retail is the merchant of record: the debt is AfroTransact's
 * own, booked to {@code 2500:<supplierId>} and paid from the operating bank account by Finance, never through Stripe
 * Connect and never netted against marketplace sellers' payables. One per purchase order.
 *
 * @param amount          total of the released invoices, in the supplier's currency
 * @param dueOn           payment date from the supplier's terms (or the default terms)
 * @param payableJournal  journal booking the payable when it was scheduled
 * @param paymentJournal  journal of the payment (null until paid)
 * @param sourceEventId   event-id of the retail.payment.released event
 */
public record SupplierPayment(UUID id, UUID poId, String poNumber, UUID supplierId, String supplierCode, Money amount,
                              List<Invoice> invoices, SupplierPaymentStatus status, LocalDate dueOn,
                              UUID payableJournal, UUID paymentJournal, String paymentReference, String holdReason,
                              String paidBy, UUID sourceEventId, Instant scheduledAt, Instant paidAt,
                              Instant updatedAt, long version) {

    /** One released supplier invoice. */
    public record Invoice(UUID invoiceId, String invoiceNumber, Money amount) {
    }

    public SupplierPayment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(poId, "poId");
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(dueOn, "dueOn");
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
        if (!amount.isPositive()) {
            throw new PaymentsRuleViolation("payments.invalid-supplier-payment",
                    "A supplier payment needs a positive amount");
        }
    }

    /** A released purchase order, booked now and due on {@code dueOn}. */
    public static SupplierPayment schedule(UUID id, UUID poId, String poNumber, UUID supplierId, String supplierCode,
                                           Money amount, List<Invoice> invoices, LocalDate dueOn, UUID journal,
                                           UUID sourceEventId, Instant now) {
        return new SupplierPayment(id, poId, poNumber, supplierId, supplierCode, amount, invoices,
                SupplierPaymentStatus.SCHEDULED, dueOn, journal, null, null, null, null, sourceEventId, now, null,
                now, 0);
    }

    /** Due (scheduled and its date has come) on {@code today}. */
    public boolean dueBy(LocalDate today) {
        return status == SupplierPaymentStatus.SCHEDULED && !dueOn.isAfter(today);
    }

    public SupplierPayment hold(String reason, Instant now) {
        if (status == SupplierPaymentStatus.PAID) {
            throw new PaymentsStateConflict("payments.supplier-payment-paid", "Supplier payment " + id
                    + " is already paid");
        }
        return with(SupplierPaymentStatus.ON_HOLD, paymentJournal, paymentReference, reason, paidBy, paidAt, now);
    }

    public SupplierPayment release(Instant now) {
        if (status != SupplierPaymentStatus.ON_HOLD) {
            throw new PaymentsStateConflict("payments.supplier-payment-not-held", "Supplier payment " + id
                    + " is not on hold");
        }
        return with(SupplierPaymentStatus.SCHEDULED, paymentJournal, paymentReference, null, paidBy, paidAt, now);
    }

    /** Paid by Finance's bank transfer {@code reference}; a held payment must be released first. */
    public SupplierPayment paid(UUID journal, String reference, String by, Instant at, Instant now) {
        if (status == SupplierPaymentStatus.ON_HOLD) {
            throw new PaymentsStateConflict("payments.supplier-payment-on-hold", "Release supplier payment " + id
                    + " before paying it");
        }
        if (status == SupplierPaymentStatus.PAID) {
            throw new PaymentsStateConflict("payments.supplier-payment-paid", "Supplier payment " + id
                    + " is already paid");
        }
        return with(SupplierPaymentStatus.PAID, journal, reference, null, by, at, now);
    }

    private SupplierPayment with(SupplierPaymentStatus s, UUID journal, String reference, String reason, String by,
                                 Instant paid, Instant now) {
        return new SupplierPayment(id, poId, poNumber, supplierId, supplierCode, amount, invoices, s, dueOn,
                payableJournal, journal, reference, reason, by, sourceEventId, scheduledAt, paid, now, version);
    }
}
