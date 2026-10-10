package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.InvoiceLine;

/** Invoice fields read from Stripe Billing webhooks. */
public record InvoiceSnapshot(String id, String customerId, String subscriptionId, String number, String status,
                              String billingReason, long amountDue, long amountPaid, String currency,
                              Instant periodStart, Instant periodEnd, String hostedInvoiceUrl, String invoicePdf,
                              int attemptCount, Instant nextPaymentAttempt, Instant created,
                              List<InvoiceLine> lines) {

    public InvoiceSnapshot {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
