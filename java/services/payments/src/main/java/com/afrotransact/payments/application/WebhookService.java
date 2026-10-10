package com.afrotransact.payments.application;

/**
 * Signed, idempotent Stripe webhook inbox: deliveries are verified and stored (event id unique), then processed
 * asynchronously from the inbox table by workers that lock rows with SKIP LOCKED.
 */
public interface WebhookService {

    /** Verifies the {@code Stripe-Signature} header and stores the event. */
    WebhookReceipt receive(String payload, String signatureHeader);

    /** Processes up to {@code max} due events (scheduled job). Returns how many were attempted. */
    int processPending(int max);
}
