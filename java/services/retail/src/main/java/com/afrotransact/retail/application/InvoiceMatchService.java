package com.afrotransact.retail.application;

import java.util.UUID;

import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.SupplierInvoice;
import com.afrotransact.retail.port.Page;

/** Supplier invoices and the PO · receipt · invoice three-way match (S074). */
public interface InvoiceMatchService {

    /** Registers and matches an invoice; retries with the same Idempotency-Key return the same invoice. */
    SupplierInvoice register(RegisterInvoiceCommand command, String idempotencyKey);

    /** Re-runs the match for an exception or held invoice (after more goods arrived or claims were settled). */
    SupplierInvoice rematch(UUID id);

    /** Finance accepts an exception; the invoice becomes payable. */
    SupplierInvoice approveException(UUID id, String note);

    SupplierInvoice reject(UUID id, String note);

    SupplierInvoice get(UUID id);

    Page<SupplierInvoice> list(InvoiceStatus status, UUID poId, int page, int size);
}
