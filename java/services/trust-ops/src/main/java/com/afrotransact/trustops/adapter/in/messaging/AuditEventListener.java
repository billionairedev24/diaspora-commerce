package com.afrotransact.trustops.adapter.in.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.application.AuditLogService;
import com.afrotransact.trustops.application.IncomingEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copies audit-worthy events from the configured topics ({@code at.trustops.audit.topics}) into the hash-chained
 * audit log. The payload is kept byte for byte. Own consumer group, so it never competes with the other listeners.
 */
@Component
class AuditEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(AuditEventListener.class);
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final AuditLogService audit;
    private final JsonMapper json;

    AuditEventListener(AuditLogService audit, JsonMapper json) {
        this.audit = audit;
        this.json = json;
    }

    @KafkaListener(id = "trust-ops-audit",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-audit",
            topics = "#{'${at.trustops.audit.topics:at.identity.events}'.split(',')}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (type.isEmpty()) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id", type, rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        Map<String, Object> payload;
        try {
            payload = rec.value() == null ? Map.of() : json.readValue(rec.value(), OBJECT);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        audit.ingest(new IncomingEvent(eventId.get(), rec.topic(), type, rec.key(),
                rec.value() == null ? "{}" : rec.value(), payload, EventHeaders.occurredAt(rec)));
    }
}
