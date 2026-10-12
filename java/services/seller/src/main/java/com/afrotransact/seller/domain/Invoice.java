package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;

/**
 * A Stripe invoice mirrored from billing webhooks for the Billing page.
 *
 * @param paidFromSalesCents what the organisation's earnings paid of it (S298: credited to the customer balance, net
 *                           of releases); the card pays {@code amountDueCents}
 */
public record Invoice(String stripeInvoiceId, String orgId, String number, Instant periodStart, Instant periodEnd,
                      long amountDueCents, long amountPaidCents, String currency, String status,
                      String billingReason, String hostedInvoiceUrl, String invoicePdf, Instant createdAt,
                      List<InvoiceLine> lines, long paidFromSalesCents) {

    public Invoice {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** An invoice from a webhook (nothing paid from sales known yet). */
    public Invoice(String stripeInvoiceId, String orgId, String number, Instant periodStart, Instant periodEnd,
                   long amountDueCents, long amountPaidCents, String currency, String status, String billingReason,
                   String hostedInvoiceUrl, String invoicePdf, Instant createdAt, List<InvoiceLine> lines) {
        this(stripeInvoiceId, orgId, number, periodStart, periodEnd, amountDueCents, amountPaidCents, currency, status,
                billingReason, hostedInvoiceUrl, invoicePdf, createdAt, lines, 0);
    }

    /** With what earnings paid of it. */
    public Invoice withPaidFromSales(long cents) {
        return new Invoice(stripeInvoiceId, orgId, number, periodStart, periodEnd, amountDueCents, amountPaidCents,
                currency, status, billingReason, hostedInvoiceUrl, invoicePdf, createdAt, lines, cents);
    }

    /** Usage lines billed from Stripe meters (S206). */
    public List<InvoiceLine> meteredLines() {
        return lines.stream().filter(InvoiceLine::metered).toList();
    }
}
