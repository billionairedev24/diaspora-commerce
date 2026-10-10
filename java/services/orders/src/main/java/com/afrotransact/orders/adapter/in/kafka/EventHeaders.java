package com.afrotransact.orders.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Platform event headers: event-id, event-type, occurred-at. */
record EventHeaders(UUID id, String type) {

    static EventHeaders of(ConsumerRecord<String, String> record) {
        return new EventHeaders(uuid(text(record, "event-id")), text(record, "event-type"));
    }

    private static String text(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static UUID uuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
