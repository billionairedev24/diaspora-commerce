package com.afrotransact.platform.events.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Factory for the exception a listener throws when a record cannot be understood at all: unparsable payload, or a
 * record without a usable {@code event-id} header in a listener that de-duplicates by it. Such a record can never
 * succeed, so {@link NonRetryableEventException} sends it to the dead-letter topic immediately (the shared error
 * handler logs it once; listeners do not log). Messages carry the event type and the record position, never payload
 * text: Jackson messages quote the source, and the handler redacts them from the {@code x-dlq-exception-*} headers.
 */
public final class MalformedEvent {

    private MalformedEvent() {
    }

    /** A payload that does not parse into what the listener expects; {@code cause} stays attached for diagnosis. */
    public static NonRetryableEventException of(String eventType, Throwable cause) {
        return new NonRetryableEventException("Malformed " + label(eventType) + " event: "
                + cause.getClass().getSimpleName(), cause);
    }

    /** A record whose {@code event-id} header is absent or not a UUID, for a listener that needs it for the inbox. */
    public static NonRetryableEventException missingEventId(String eventType, ConsumerRecord<?, ?> rec) {
        return new NonRetryableEventException("Missing or invalid event-id header on " + label(eventType) + " at "
                + rec.topic() + "-" + rec.partition() + "@" + rec.offset());
    }

    private static String label(String eventType) {
        return eventType == null || eventType.isBlank() ? "(untyped)" : eventType;
    }
}
