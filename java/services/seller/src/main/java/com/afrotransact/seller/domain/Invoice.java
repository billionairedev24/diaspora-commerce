package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;

/** A Stripe invoice mirrored from billing webhooks for the Billing page. */
public record Invoice(String stripeInvoiceId, String orgId, String number, Instant periodStart, Instant periodEnd,
                      long amountDueCents, long amountPaidCents, String currency, String status,
                      String billingReason, String hostedInvoiceUrl, String invoicePdf, Instant createdAt,
                      List<InvoiceLine> lines) {

    public Invoice {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Usage lines billed from Stripe meters (S206). */
    public List<InvoiceLine> meteredLines() {
        return lines.stream().filter(InvoiceLine::metered).toList();
    }
}
