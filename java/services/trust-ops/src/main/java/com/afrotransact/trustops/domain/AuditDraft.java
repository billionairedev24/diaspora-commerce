package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * An audit record before it is linked into the chain. Instants are truncated to microseconds (PostgreSQL
 * {@code timestamptz} precision) so the hash computed now equals the hash recomputed from the stored row.
 *
 * @param sourceEventId event id of the consumed Kafka event; null for trust-ops' own actions
 * @param sourceTopic   Kafka topic, or {@code trust-ops} for own actions
 * @param eventType     event type, for example {@code identity.role.granted}
 * @param payload       JSON text exactly as hashed and stored
 */
public record AuditDraft(UUID id, UUID sourceEventId, String sourceTopic, String eventType, AuditFacts facts,
                         String payload, Instant occurredAt, Instant recordedAt) {

    public AuditDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(facts, "facts");
        payload = payload == null ? "{}" : payload;
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt").truncatedTo(ChronoUnit.MICROS);
        occurredAt = (occurredAt == null ? recordedAt : occurredAt).truncatedTo(ChronoUnit.MICROS);
    }
}
