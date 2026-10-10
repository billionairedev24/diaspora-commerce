package com.afrotransact.bookings.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.bookings.application.PaymentEventIngest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka adapter for payments-service events ({@code at.payment.events}; headers event-id, event-type). Parsing and
 * transport live here; the inbox de-duplication and booking changes are in {@link PaymentEventIngest}, so the
 * behaviour is testable without Kafka. Disabled with {@code at.bookings.payment-events.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "at.bookings.payment-events", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PaymentEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventsListener.class);

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
        if (eventId == null || type == null || !type.startsWith("payments.intent.")) {
            return;
        }
        JsonNode payload;
        try {
            payload = json.readTree(record.value());
        } catch (RuntimeException e) {
            LOG.warn("Unreadable payment event {} skipped: {}", eventId, e.getMessage());
            return;
        }
        ingest.handle(UUID.fromString(eventId), type, payload.path("orderId").asString(record.key()),
                payload.path("status").asString(null), payload.path("failureMessage").asString(null));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
