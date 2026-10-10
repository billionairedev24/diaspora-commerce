package com.afrotransact.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.SupplierPayment;
import com.afrotransact.payments.domain.SupplierPaymentStatus;

/**
 * AfroTransact Retail supplier payments (S226). retail-service releases a purchase order's matched invoices; payments
 * books what Retail (the merchant of record) owes the supplier and schedules the payment by the supplier's terms;
 * Finance pays it from the operating bank account and records the bank reference here.
 */
public interface SupplierPaymentService {

    /**
     * {@code retail.payment.released}: books Dr 1400 / Cr 2500:supplier and schedules the payment. Idempotent per event
     * and per purchase order. Returns false when nothing was booked (redelivery, duplicate PO, incomplete event).
     */
    boolean released(SupplierPaymentNotice notice);

    /** Staff with payments:read (platform-wide) or services: newest due date first. */
    List<SupplierPayment> list(SupplierPaymentStatus status, UUID supplierId, LocalDate dueBy, int limit);

    SupplierPayment get(UUID id);

    /** Finance (payouts:approve) records the bank transfer: Dr 2500:supplier / Cr 1050. Idempotent per reference. */
    SupplierPayment markPaid(UUID id, String paymentReference, Instant paidAt);

    SupplierPayment hold(UUID id, String reason);

    SupplierPayment release(UUID id);
}
