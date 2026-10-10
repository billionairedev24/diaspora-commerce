package com.afrotransact.platform.events;

import java.util.List;
import java.util.UUID;

/** Persistence port for the transactional outbox table. */
public interface OutboxStore {

    void append(EventEnvelope envelope);

    /** Locks and returns up to {@code limit} unpublished events (SKIP LOCKED so relays can run in parallel). */
    List<EventEnvelope> lockBatch(int limit);

    void markPublished(List<UUID> ids);
}
