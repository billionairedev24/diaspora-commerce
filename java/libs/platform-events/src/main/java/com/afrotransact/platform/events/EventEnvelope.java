package com.afrotransact.platform.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire format of every domain event (see contracts/asyncapi/events.yaml).
 *
 * @param id            unique event id; consumers de-duplicate on it
 * @param type          dotted event type, for example {@code catalog.product.created}
 * @param aggregateType aggregate name, used as the Kafka topic suffix
 * @param aggregateId   aggregate id, used as the Kafka message key so per-aggregate order is kept
 * @param occurredAt    when the change was committed
 * @param payload       JSON payload
 */
public record EventEnvelope(
        UUID id,
        String type,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String payload) {

    public EventEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
    }

    /** Kafka topic for this event: {@code at.<aggregateType>.events}. */
    public String topic() {
        return "at." + aggregateType + ".events";
    }
}
