package com.afrotransact.platform.events;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes events to the outbox in the caller's transaction (MANDATORY: never publish outside one). */
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final OutboxStore store;
    private final EventSerializer serializer;
    private final Clock clock;

    public OutboxDomainEventPublisher(OutboxStore store, EventSerializer serializer, Clock clock) {
        this.store = store;
        this.serializer = serializer;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String type, String aggregateType, String aggregateId, Object payload) {
        store.append(new EventEnvelope(UUID.randomUUID(), type, aggregateType, aggregateId,
                Instant.now(clock), serializer.toJson(payload)));
    }
}
