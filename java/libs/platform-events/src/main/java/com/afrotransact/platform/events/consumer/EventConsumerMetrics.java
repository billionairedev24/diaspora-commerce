package com.afrotransact.platform.events.consumer;

/**
 * Counters for the consumer error path. On {@code /actuator/prometheus} they appear as
 * {@code events_consumer_retries_total{consumer,topic}}, {@code events_dead_lettered_total{consumer,topic,reason}}
 * and {@code events_dead_letter_failures_total{consumer,topic}}.
 */
public interface EventConsumerMetrics {

    void retried(String consumer, String topic);

    void deadLettered(String consumer, String topic, String reason);

    /** The record could not be written to the DLQ; its offset stays uncommitted and it is retried. */
    void deadLetterFailed(String consumer, String topic);
}
