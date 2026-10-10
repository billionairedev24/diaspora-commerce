package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Invoice;

/** Invoices mirrored from Stripe Billing webhooks. */
public interface InvoiceRepository {

    void upsert(Invoice invoice);

    List<Invoice> list(String orgId, int limit);

    /** One invoice of the organisation by Stripe invoice id. */
    Optional<Invoice> find(String orgId, String stripeInvoiceId);
}
