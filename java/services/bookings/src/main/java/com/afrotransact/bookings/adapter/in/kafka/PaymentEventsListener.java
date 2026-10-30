package com.afrotransact.bookings.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.bookings.application.PaymentEventIngest;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka adapter for payments-service events ({@code at.payment.events}; headers event-id, event-type): intent events
 * of bookings' payments, including off-session balance charges ({@code parentPaymentId}, {@code offSession}).
 * Parsing and transport live here; the inbox de-duplication and booking changes are in {@link PaymentEventIngest},
 * so the behaviour is testable without Kafka. Disabled with {@code at.bookings.payment-events.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "at.bookings.payment-events", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PaymentEventsListener {

    private final PaymentEventIngest ingest;
    private final JsonMapper json;

    PaymentEventsListener(PaymentEventIngest ingest, JsonMapper json) {
        this.ingest = ingest;
        this.json = json;
    }

    @KafkaListener(topics = "${at.bookings.payment-events.topic:at.payment.events}",
            groupId = "${spring.kafka.consumer.group-id:bookings-service}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventId = header(record, "event-id");
        String type = header(record, "event-type");
        if (type == null || !type.startsWith("payments.intent.")) {
            return;
        }
        if (eventId == null) {
            throw MalformedEvent.missingEventId(type, record);
        }
        JsonNode payload;
        try {
            payload = json.readTree(record.value());
        } catch (RuntimeException e) {
            throw MalformedEvent.of(type, e);
        }
        ingest.handle(UUID.fromString(eventId), type, new PaymentEventIngest.IntentEvent(
                payload.path("orderId").asString(record.key()), uuid(payload, "paymentId"),
                payload.path("status").asString(null), payload.path("failureMessage").asString(null),
                uuid(payload, "parentPaymentId"), payload.path("offSession").asBoolean(false)));
    }

    private static UUID uuid(JsonNode payload, String field) {
        String value = payload.path(field).asString("");
        return value.isEmpty() ? null : UUID.fromString(value);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
