package com.afrotransact.platform.events;

/**
 * Moves committed outbox rows to the event bus. In cloud environments Debezium CDC can replace
 * the polling relay; the polling relay is the default for local and small deployments.
 */
public interface OutboxRelay {

    /** Relays one batch and returns how many events were published. */
    int relayBatch();
}
