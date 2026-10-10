package com.afrotransact.mcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.afrotransact.mcp.application.AuditRecorderService;
import com.afrotransact.mcp.port.AuditSpool;
import com.afrotransact.mcp.port.AuditTrail;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * S207 durable audit end to end with the real Kafka adapter and the real Valkey spool. The outage is simulated by a
 * switch in front of the Kafka adapter (a broker that refuses), so the test does not wait out producer timeouts.
 */
@SpringBootTest(properties = {
    "at.mcp.audit.replay-interval=1h",
    // own stream: other cached IT contexts run the replay job against the shared Valkey
    "at.mcp.audit.spool-stream=mcp:audit:spool:durable-it"
})
@ActiveProfiles("local")
@Import(DurableAuditIT.Outage.class)
class DurableAuditIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", ItContainers.KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.url", ItContainers::valkeyUrl);
    }

    private final AuditRecorderService recorder;
    private final AuditSpool spool;
    private final OutageSwitch outage;
    private final JsonMapper json;

    @Autowired
    DurableAuditIT(AuditRecorderService recorder, AuditSpool spool, OutageSwitch outage, JsonMapper json) {
        this.recorder = recorder;
        this.spool = spool;
        this.outage = outage;
        this.json = json;
    }

    @Test
    void eventsSurviveAKafkaOutageAndAreReplayedInOrder() {
        String subject = "audit-" + UUID.randomUUID();
        outage.down = true;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AuditTrail.ToolInvoked e = event(subject, i);
            ids.add(e.eventId());
            recorder.record(e);
        }
        assertThat(spool.size()).isEqualTo(5);

        outage.down = false;
        // broker is back but the backlog is not drained yet: new events queue behind it
        AuditTrail.ToolInvoked late = event(subject, 5);
        ids.add(late.eventId());
        recorder.record(late);

        assertThat(recorder.replaySpool()).isEqualTo(6);
        assertThat(spool.size()).isZero();
        assertThat(eventIdsFor(subject, 6)).containsExactlyElementsOf(ids);

        AuditTrail.ToolInvoked direct = event(subject, 6);
        recorder.record(direct);
        assertThat(spool.size()).as("direct path again once drained").isZero();
    }

    private static AuditTrail.ToolInvoked event(String subject, int n) {
        return new AuditTrail.ToolInvoked(UUID.randomUUID().toString(), "get_cart", "SHOPPER", subject, "agent",
                null, "sha256:" + n, "ok", n, Instant.now());
    }

    private List<String> eventIdsFor(String subject, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ItContainers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "durable-audit-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> ids = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("at.mcp-tool.events"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (ids.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    if (subject.equals(rec.key())) {
                        ids.add(json.readTree(rec.value()).path("eventId").asString());
                    }
                }
            }
        }
        return ids;
    }

    /** Broker that refuses while {@code down}; otherwise the real Kafka adapter. */
    static final class OutageSwitch implements AuditTrail {

        private final AuditTrail kafka;
        private volatile boolean down;

        OutageSwitch(AuditTrail kafka) {
            this.kafka = kafka;
        }

        @Override
        public CompletionStage<Void> publish(ToolInvoked event) {
            if (down) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
            }
            return kafka.publish(event);
        }
    }

    @TestConfiguration
    static class Outage {

        @Bean
        @Primary
        OutageSwitch outageSwitch(@Qualifier("kafkaAuditTrail") AuditTrail kafka) {
            return new OutageSwitch(kafka);
        }
    }
}
