package com.afrotransact.platform.events;

/**
 * Port used by application services to publish domain events. The default implementation
 * ({@link OutboxDomainEventPublisher}) writes to the outbox table inside the caller's transaction,
 * so an event is published if and only if the business change commits.
 */
public interface DomainEventPublisher {

    void publish(String type, String aggregateType, String aggregateId, Object payload);
}
