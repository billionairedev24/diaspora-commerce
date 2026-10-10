package com.afrotransact.platform.events;

import java.util.UUID;

/** Consumer-side idempotency: records processed event ids so re-delivered events are skipped. */
public interface InboxDeduplicator {

    /**
     * Records the event as processed by {@code consumer} in the current transaction.
     *
     * @return true when this is the first time the event is seen and the handler should run
     */
    boolean firstDelivery(String consumer, UUID eventId);
}
