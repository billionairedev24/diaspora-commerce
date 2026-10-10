package com.afrotransact.platform.events;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/** Outbox/inbox wiring. Enable the polling relay with {@code at.events.relay-enabled=true}. */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
    "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"})
@EnableScheduling
public class PlatformEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock platformClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventSerializer eventSerializer() {
        return new JacksonEventSerializer(JsonMapper.builder().findAndAddModules().build());
    }

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    @ConditionalOnMissingBean
    public OutboxStore outboxStore(JdbcClient jdbc) {
        return new JdbcOutboxStore(jdbc);
    }

    @Bean
    @ConditionalOnBean(OutboxStore.class)
    @ConditionalOnMissingBean
    public DomainEventPublisher domainEventPublisher(OutboxStore store, EventSerializer serializer, Clock clock) {
        return new OutboxDomainEventPublisher(store, serializer, clock);
    }

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    @ConditionalOnMissingBean
    public InboxDeduplicator inboxDeduplicator(JdbcClient jdbc) {
        return new JdbcInboxDeduplicator(jdbc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "at.events", name = "relay-enabled", havingValue = "true")
    @ConditionalOnBean({OutboxStore.class, KafkaTemplate.class})
    public OutboxRelay kafkaOutboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka,
                                        @Value("${at.events.relay-batch-size:200}") int batchSize) {
        return new KafkaOutboxRelay(store, kafka, batchSize);
    }
}
