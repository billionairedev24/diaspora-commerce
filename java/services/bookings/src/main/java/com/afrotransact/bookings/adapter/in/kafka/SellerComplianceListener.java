package com.afrotransact.bookings.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.application.SellerComplianceIngest;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka adapter for seller-service enforcement events ({@code at.seller-compliance.events}, key = org id; headers
 * event-id, event-type; payload SellerEnforcement). Only {@code seller.listings.hidden|restored} are used.
 */
@Component
@ConditionalOnProperty(prefix = "at.bookings.seller-compliance-events", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SellerComplianceListener {

    private final SellerComplianceIngest ingest;
    private final JsonMapper json;

    SellerComplianceListener(SellerComplianceIngest ingest, JsonMapper json) {
        this.ingest = ingest;
        this.json = json;
    }

    @KafkaListener(topics = "${at.bookings.seller-compliance-events.topic:at.seller-compliance.events}",
            groupId = "${spring.kafka.consumer.group-id:bookings-service}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String type = header(record, "event-type");
        if (type == null || !type.startsWith("seller.listings.")) {
            return;
        }
        UUID eventId = eventId(record, type);
        JsonNode payload;
        try {
            payload = json.readTree(record.value());
        } catch (RuntimeException e) {
            throw MalformedEvent.of(type, e);
        }
        String occurred = payload.path("occurredAt").asString(null);
        ingest.handle(eventId, type, payload.path("orgId").asString(record.key()),
                payload.path("reason").asString(null), occurred == null ? null : Instant.parse(occurred));
    }

    private static UUID eventId(ConsumerRecord<String, String> record, String type) {
        String raw = header(record, "event-id");
        if (raw != null) {
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ex) {
                throw MalformedEvent.missingEventId(type, record);
            }
        }
        throw MalformedEvent.missingEventId(type, record);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
