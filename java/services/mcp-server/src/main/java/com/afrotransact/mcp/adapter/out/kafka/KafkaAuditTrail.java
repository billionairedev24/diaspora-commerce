package com.afrotransact.mcp.adapter.out.kafka;

import java.nio.charset.StandardCharsets;

import com.afrotransact.mcp.port.AuditTrail;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code mcp.tool.invoked} with the same wire format as the platform outbox relay (topic
 * {@code at.mcp-tool.events}, key = subject, headers event-id / event-type / occurred-at, JSON payload). The MCP
 * server is stateless (no database, so no outbox table): events are sent directly and every call is also written to
 * the {@code com.afrotransact.mcp.audit} log, so a broker outage never loses the audit trail.
 */
@Component
class KafkaAuditTrail implements AuditTrail {

    static final String TOPIC = "at.mcp-tool.events";
    static final String TYPE = "mcp.tool.invoked";
    private static final Logger LOG = LoggerFactory.getLogger(KafkaAuditTrail.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    KafkaAuditTrail(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void record(ToolInvoked event) {
        ProducerRecord<String, String> rec = new ProducerRecord<>(TOPIC, event.subject(),
                json.writeValueAsString(event));
        rec.headers().add(new RecordHeader("event-id", event.eventId().getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", TYPE.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("occurred-at", event.at().toString().getBytes(StandardCharsets.UTF_8)));
        try {
            kafka.send(rec).whenComplete((result, failure) -> {
                if (failure != null) {
                    LOG.warn("mcp.tool.invoked {} not published ({}); kept in the audit log", event.eventId(),
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("mcp.tool.invoked {} not published ({}); kept in the audit log", event.eventId(),
                    e.getClass().getSimpleName());
        }
    }
}
