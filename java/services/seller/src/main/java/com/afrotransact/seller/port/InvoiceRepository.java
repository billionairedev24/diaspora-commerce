package com.afrotransact.seller.port;

import java.util.List;

import com.afrotransact.seller.domain.Invoice;

/** Invoices mirrored from Stripe Billing webhooks. */
public interface InvoiceRepository {

    void upsert(Invoice invoice);

    List<Invoice> list(String orgId, int limit);
}
