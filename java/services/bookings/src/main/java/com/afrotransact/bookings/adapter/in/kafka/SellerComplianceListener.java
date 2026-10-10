package com.afrotransact.bookings.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.application.SellerComplianceIngest;
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
 * Kafka adapter for seller-service enforcement events ({@code at.seller-compliance.events}, key = org id; headers
 * event-id, event-type; payload SellerEnforcement). Only {@code seller.listings.hidden|restored} are used.
 */
@Component
@ConditionalOnProperty(prefix = "at.bookings.seller-compliance-events", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SellerComplianceListener {

    private static final Logger LOG = LoggerFactory.getLogger(SellerComplianceListener.class);

    private final SellerComplianceIngest ingest;
    private final JsonMapper json;

    SellerComplianceListener(SellerComplianceIngest ingest, JsonMapper json) {
        this.ingest = ingest;
        this.json = json;
    }

    @KafkaListener(topics = "${at.bookings.seller-compliance-events.topic:at.seller-compliance.events}",
            groupId = "${spring.kafka.consumer.group-id:bookings-service}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String eventId = header(record, "event-id");
        String type = header(record, "event-type");
        if (eventId == null || type == null || !type.startsWith("seller.listings.")) {
            return;
        }
        JsonNode payload;
        try {
            payload = json.readTree(record.value());
        } catch (RuntimeException e) {
            LOG.warn("Unreadable seller compliance event {} skipped: {}", eventId, e.getMessage());
            return;
        }
        String occurred = payload.path("occurredAt").asString(null);
        ingest.handle(UUID.fromString(eventId), type, payload.path("orgId").asString(record.key()),
                payload.path("reason").asString(null), occurred == null ? null : Instant.parse(occurred));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
