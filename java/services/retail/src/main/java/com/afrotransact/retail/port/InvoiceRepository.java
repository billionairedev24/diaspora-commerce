package com.afrotransact.retail.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.SupplierInvoice;

/** Persistence port for supplier invoices. */
public interface InvoiceRepository {

    SupplierInvoice insert(SupplierInvoice invoice);

    SupplierInvoice update(SupplierInvoice invoice);

    Optional<SupplierInvoice> findById(UUID id);

    Optional<SupplierInvoice> findBySupplierAndNumber(UUID supplierId, String invoiceNumber);

    List<SupplierInvoice> findByPo(UUID poId);

    Page<SupplierInvoice> page(InvoiceStatus status, UUID poId, int page, int size);
}
