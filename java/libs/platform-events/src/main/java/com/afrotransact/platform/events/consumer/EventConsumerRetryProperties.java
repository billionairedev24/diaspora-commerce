package com.afrotransact.platform.events.consumer;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retry and dead-letter settings for every Spring Kafka listener container, bound from
 * {@code at.events.consumer.retry.*}.
 *
 * @param enabled                set to {@code false} to opt out of the shared error handler entirely
 * @param initialInterval        first backoff delay
 * @param multiplier             backoff growth factor
 * @param maxInterval            upper bound for a single backoff delay
 * @param jitter                 random +/- offset applied to each backoff delay so replicas do not retry in step
 * @param maxAttempts            total deliveries of a record (first try included) before it is dead-lettered
 * @param dlqSuffix              appended to the original topic to name the dead-letter topic
 * @param sendTimeout            how long to wait for the broker to acknowledge the dead-letter record
 * @param maxMessageLength       cap for the exception message header
 * @param nonRetryableExceptions extra fully-qualified exception classes that go to the DLQ without retries
 * @param retryableExceptions    fully-qualified exception classes that are always retried
 */
@ConfigurationProperties(prefix = "at.events.consumer.retry")
public record EventConsumerRetryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("30s") Duration maxInterval,
        @DefaultValue("250ms") Duration jitter,
        @DefaultValue("6") int maxAttempts,
        @DefaultValue(".dlq") String dlqSuffix,
        @DefaultValue("30s") Duration sendTimeout,
        @DefaultValue("200") int maxMessageLength,
        @DefaultValue List<String> nonRetryableExceptions,
        @DefaultValue List<String> retryableExceptions) {

    public EventConsumerRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("at.events.consumer.retry.max-attempts must be >= 1");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("at.events.consumer.retry.multiplier must be >= 1.0");
        }
        if (initialInterval.isNegative() || maxInterval.compareTo(initialInterval) < 0 || jitter.isNegative()) {
            throw new IllegalArgumentException("at.events.consumer.retry: need 0 <= initial-interval <= "
                    + "max-interval and jitter >= 0");
        }
        if (!dlqSuffix.startsWith(".") || dlqSuffix.length() < 2) {
            throw new IllegalArgumentException("at.events.consumer.retry.dlq-suffix must look like '.dlq'");
        }
        nonRetryableExceptions = List.copyOf(nonRetryableExceptions);
        retryableExceptions = List.copyOf(retryableExceptions);
    }
}
