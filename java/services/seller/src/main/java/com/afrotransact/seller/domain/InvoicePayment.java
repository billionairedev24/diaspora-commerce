package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One settlement of an invoice.
 *
 * @param reference the PaymentIntent id, payments' withholding id or the credit / waiver key; unique per invoice and
 *                  source, so a replay settles nothing twice
 * @param note      for a waiver, the reason shown to the seller
 */
public record InvoicePayment(UUID id, String invoiceId, String orgId, PaymentSource source, long amountCents,
                             String status, String reference, String note, Instant createdAt) {

    public static final String APPLIED = "APPLIED";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String REVERSED = "REVERSED";
}
