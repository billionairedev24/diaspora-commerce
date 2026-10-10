package com.afrotransact.seller.port;

/** De-duplicates Stripe webhook deliveries by event id (Stripe retries for up to 3 days). */
public interface WebhookInbox {

    /** Claims {@code eventId} in the current transaction; false when it was processed before. */
    boolean firstDelivery(String eventId, String endpoint, String type, String accountId, long created);

    void outcome(String eventId, String outcome);
}
