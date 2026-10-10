package com.afrotransact.platform.events;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/** Polling relay: publishes outbox rows to Kafka keyed by aggregate id, then marks them published. */
public class KafkaOutboxRelay implements OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public KafkaOutboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka, int batchSize) {
        this.store = store;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${at.events.relay-interval:500ms}")
    public void tick() {
        relayBatch();
    }

    @Override
    @Transactional
    public int relayBatch() {
        List<EventEnvelope> batch = store.lockBatch(batchSize);
        List<UUID> published = new ArrayList<>(batch.size());
        for (EventEnvelope e : batch) {
            ProducerRecord<String, String> rec = new ProducerRecord<>(e.topic(), e.aggregateId(), e.payload());
            rec.headers().add(new RecordHeader("event-id", e.id().toString().getBytes(StandardCharsets.UTF_8)));
            rec.headers().add(new RecordHeader("event-type", e.type().getBytes(StandardCharsets.UTF_8)));
            rec.headers().add(new RecordHeader("occurred-at",
                    e.occurredAt().toString().getBytes(StandardCharsets.UTF_8)));
            CompletableFuture<SendResult<String, String>> future = kafka.send(rec);
            try {
                future.get(10, TimeUnit.SECONDS);
                published.add(e.id());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException ex) {
                LOG.warn("Outbox relay stopped at event {} ({}); will retry", e.id(), e.type(), ex);
                break;
            }
        }
        store.markPublished(published);
        return published.size();
    }
}
