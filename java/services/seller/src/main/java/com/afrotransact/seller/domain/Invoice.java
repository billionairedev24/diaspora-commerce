package com.afrotransact.seller.domain;

import java.time.Instant;

/** A Stripe invoice mirrored from billing webhooks for the Billing page. */
public record Invoice(String stripeInvoiceId, String orgId, String number, Instant periodStart, Instant periodEnd,
                      long amountDueCents, long amountPaidCents, String currency, String status,
                      String billingReason, String hostedInvoiceUrl, String invoicePdf, Instant createdAt) {
}
