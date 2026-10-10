package com.afrotransact.trustops.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A consumed platform event.
 *
 * @param payloadJson the payload exactly as received (stored and hashed as is)
 * @param payload     the same payload parsed into a JSON object
 * @param occurredAt  the {@code occurred-at} header, when present
 */
public record IncomingEvent(UUID eventId, String topic, String type, String key, String payloadJson,
                            Map<String, Object> payload, Instant occurredAt) {

    public IncomingEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Map.of() : payload;
    }
}
