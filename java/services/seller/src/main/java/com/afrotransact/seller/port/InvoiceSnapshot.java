package com.afrotransact.seller.port;

import java.time.Instant;

/** Invoice fields read from Stripe Billing webhooks. */
public record InvoiceSnapshot(String id, String customerId, String subscriptionId, String number, String status,
                              String billingReason, long amountDue, long amountPaid, String currency,
                              Instant periodStart, Instant periodEnd, String hostedInvoiceUrl, String invoicePdf,
                              int attemptCount, Instant nextPaymentAttempt, Instant created) {
}
