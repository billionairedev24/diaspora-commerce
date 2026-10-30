package com.afrotransact.platform.events.consumer;

import io.micrometer.core.instrument.MeterRegistry;

/** Micrometer-backed {@link EventConsumerMetrics}. */
class MicrometerEventConsumerMetrics implements EventConsumerMetrics {

    static final String RETRIES = "events.consumer.retries";
    static final String DEAD_LETTERED = "events.dead.lettered";
    static final String DEAD_LETTER_FAILURES = "events.dead.letter.failures";

    private final MeterRegistry registry;

    MicrometerEventConsumerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void retried(String consumer, String topic) {
        registry.counter(RETRIES, "consumer", consumer, "topic", topic).increment();
    }

    @Override
    public void deadLettered(String consumer, String topic, String reason) {
        registry.counter(DEAD_LETTERED, "consumer", consumer, "topic", topic, "reason", reason).increment();
    }

    @Override
    public void deadLetterFailed(String consumer, String topic) {
        registry.counter(DEAD_LETTER_FAILURES, "consumer", consumer, "topic", topic).increment();
    }
}
