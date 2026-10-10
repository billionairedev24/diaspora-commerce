package com.afrotransact.trustops.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.application.HandoffRequest;
import com.afrotransact.trustops.application.SupportCaseService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Opens a support case for every Lola hand-off ({@code ai.handoff.requested} on {@code at.ai-handoff.events}). */
@Component
class AiHandoffEventListener {

    static final String HANDOFF_REQUESTED = "ai.handoff.requested";

    private static final Logger LOG = LoggerFactory.getLogger(AiHandoffEventListener.class);

    private final SupportCaseService cases;
    private final JsonMapper json;

    AiHandoffEventListener(SupportCaseService cases, JsonMapper json) {
        this.cases = cases;
        this.json = json;
    }

    /** ai-gateway's AiHandoffRequested payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HandoffMessage(UUID handoffId, String sessionId, String subject, String reason, String summary,
                          Instant requestedAt) {
    }

    @KafkaListener(id = "trust-ops-ai-handoffs",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-support",
            topics = "${at.trustops.consumers.handoff-topic:at.ai-handoff.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (!HANDOFF_REQUESTED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id", type, rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        HandoffMessage m;
        try {
            m = json.readValue(rec.value(), HandoffMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.handoffId() == null) {
            LOG.warn("Skipping {} event {} without handoffId", type, eventId.get());
            return;
        }
        cases.openFromHandoff(eventId.get(), new HandoffRequest(m.handoffId(), m.sessionId(), m.subject(),
                m.reason(), m.summary(), m.requestedAt()));
    }
}
