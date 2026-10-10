package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.port.StripeWebhookVerifier.VerifiedEvent;

/** Stripe event inbox: stored on receipt (id unique), processed asynchronously. */
public interface WebhookInbox {

    /** Stores the event as RECEIVED (to process) or IGNORED (unknown type); false for a duplicate delivery. */
    boolean store(VerifiedEvent event, boolean handled, Instant now);

    /** Locks the oldest due RECEIVED event (FOR UPDATE SKIP LOCKED) so several workers can run. */
    Optional<InboxEvent> lockNext(Instant now);

    void markProcessed(String id, Instant now);

    /** Records a failed attempt; {@code giveUp} parks it as FAILED for an operator. */
    void markFailed(String id, String error, Instant nextAttemptAt, boolean giveUp);

    Optional<InboxEvent> find(String id);

    /** A stored Stripe event. */
    record InboxEvent(String id, String type, String account, Instant created, String payload, String status,
                      int attempts) {
    }
}
