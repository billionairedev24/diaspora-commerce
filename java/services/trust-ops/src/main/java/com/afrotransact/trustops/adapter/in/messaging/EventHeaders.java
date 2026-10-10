package com.afrotransact.trustops.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Reads the platform event headers (event-id, event-type, occurred-at) written by the outbox relays. */
final class EventHeaders {

    static final String EVENT_ID = "event-id";
    static final String EVENT_TYPE = "event-type";
    static final String OCCURRED_AT = "occurred-at";

    private EventHeaders() {
    }

    static Optional<String> text(ConsumerRecord<?, ?> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null || h.value() == null ? Optional.empty()
                : Optional.of(new String(h.value(), StandardCharsets.UTF_8));
    }

    static String type(ConsumerRecord<?, ?> rec) {
        return text(rec, EVENT_TYPE).orElse("");
    }

    static Optional<UUID> eventId(ConsumerRecord<?, ?> rec) {
        try {
            return text(rec, EVENT_ID).map(UUID::fromString);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    static Instant occurredAt(ConsumerRecord<?, ?> rec) {
        try {
            return text(rec, OCCURRED_AT).map(Instant::parse).orElse(null);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
