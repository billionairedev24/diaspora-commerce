package com.afrotransact.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code retail.payment.released} from retail-service: a purchase order closed after the three-way match and these
 * invoices may be paid.
 *
 * @param eventId    the event's event-id header (inbox key)
 * @param releasedAt the event's occurred-at (null: now)
 * @param dueOn      payment date when retail-service sends one; else the default supplier terms apply
 */
public record SupplierPaymentNotice(UUID eventId, UUID poId, String poNumber, UUID supplierId, String supplierCode,
                                    String currency, long amountMinor, List<Invoice> invoices, Instant releasedAt,
                                    LocalDate dueOn) {

    public SupplierPaymentNotice {
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
    }

    /** One released invoice. */
    public record Invoice(UUID invoiceId, String invoiceNumber, String currency, long amountMinor) {
    }
}
