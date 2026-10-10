package com.afrotransact.mcp.adapter.out.kafka;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.afrotransact.mcp.port.AuditTrail;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code mcp.tool.invoked} with the same wire format as the platform outbox relay (topic
 * {@code at.mcp-tool.events}, key = subject, headers event-id / event-type / occurred-at, JSON payload). The MCP
 * server has no database, so durability comes from the audit spool (see {@code AuditRecorderService}), not an outbox
 * table.
 */
@Component
class KafkaAuditTrail implements AuditTrail {

    static final String TOPIC = "at.mcp-tool.events";
    static final String TYPE = "mcp.tool.invoked";

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    KafkaAuditTrail(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public CompletionStage<Void> publish(ToolInvoked event) {
        ProducerRecord<String, String> rec = new ProducerRecord<>(TOPIC, event.subject(),
                json.writeValueAsString(event));
        rec.headers().add(new RecordHeader("event-id", event.eventId().getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", TYPE.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("occurred-at", event.at().toString().getBytes(StandardCharsets.UTF_8)));
        CompletableFuture<Void> done = new CompletableFuture<>();
        kafka.send(rec).whenComplete((result, failure) -> {
            if (failure != null) {
                done.completeExceptionally(failure);
            } else {
                done.complete(null);
            }
        });
        return done;
    }
}
