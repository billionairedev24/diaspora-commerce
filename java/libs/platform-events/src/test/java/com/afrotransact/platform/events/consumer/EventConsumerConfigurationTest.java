package com.afrotransact.platform.events.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOffExecution;

class EventConsumerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                    EventConsumerErrorHandlingAutoConfiguration.class));

    private static EventConsumerRetryProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("at.events.consumer.retry", EventConsumerRetryProperties.class);
    }

    @Test
    void defaultsMatchThePlatformPolicy() {
        EventConsumerRetryProperties p = bind(Map.of());
        assertThat(p.enabled()).isTrue();
        assertThat(p.initialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.multiplier()).isEqualTo(2.0);
        assertThat(p.maxInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.maxAttempts()).isEqualTo(6);
        assertThat(p.dlqSuffix()).isEqualTo(".dlq");
        assertThat(p.nonRetryableExceptions()).isEmpty();
    }

    @Test
    void propertiesBindFromAtEventsConsumerRetry() {
        EventConsumerRetryProperties p = bind(Map.of(
                "at.events.consumer.retry.initial-interval", "500ms",
                "at.events.consumer.retry.multiplier", "3",
                "at.events.consumer.retry.max-interval", "10s",
                "at.events.consumer.retry.max-attempts", "4",
                "at.events.consumer.retry.jitter", "0",
                "at.events.consumer.retry.non-retryable-exceptions[0]", "com.example.Rejected"));
        assertThat(p.initialInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.multiplier()).isEqualTo(3.0);
        assertThat(p.maxInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.maxAttempts()).isEqualTo(4);
        assertThat(p.jitter()).isZero();
        assertThat(p.nonRetryableExceptions()).containsExactly("com.example.Rejected");
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> bind(Map.of("at.events.consumer.retry.max-attempts", "0"))).hasRootCauseInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("at.events.consumer.retry.dlq-suffix", "dlq"))).hasRootCauseInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void backoffGrowsExponentiallyIsCappedAndStopsAfterMaxAttempts() {
        EventConsumerRetryProperties p = bind(Map.of("at.events.consumer.retry.jitter", "0"));
        BackOffExecution execution = EventConsumerErrorHandlingAutoConfiguration.backOff(p).start();
        List<Long> delays = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            delays.add(next);
        }
        // 6 deliveries in total means 5 waits between them.
        assertThat(delays).containsExactly(1000L, 2000L, 4000L, 8000L, 16000L);

        EventConsumerRetryProperties capped = bind(Map.of("at.events.consumer.retry.jitter", "0",
                "at.events.consumer.retry.max-attempts", "9"));
        BackOffExecution longer = EventConsumerErrorHandlingAutoConfiguration.backOff(capped).start();
        List<Long> all = new ArrayList<>();
        for (long next = longer.nextBackOff(); next != BackOffExecution.STOP; next = longer.nextBackOff()) {
            all.add(next);
        }
        assertThat(all).containsExactly(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L);
    }

    @Test
    void backoffAddsBoundedJitter() {
        EventConsumerRetryProperties p = bind(Map.of("at.events.consumer.retry.jitter", "250ms"));
        for (int run = 0; run < 50; run++) {
            BackOffExecution execution = EventConsumerErrorHandlingAutoConfiguration.backOff(p).start();
            assertThat(execution.nextBackOff()).isBetween(750L, 1250L);
            assertThat(execution.nextBackOff()).isBetween(1400L, 2800L);
        }
        Set<Long> firstDelays = new HashSet<>();
        for (int run = 0; run < 50; run++) {
            firstDelays.add(EventConsumerErrorHandlingAutoConfiguration.backOff(p).start().nextBackOff());
        }
        assertThat(firstDelays.size()).as("jitter spreads the delays").isGreaterThan(1);
    }

    @Test
    void errorHandlerBeanIsRegisteredByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CommonErrorHandler.class);
            assertThat(ctx.getBean(CommonErrorHandler.class)).isInstanceOf(EventConsumerErrorHandler.class)
                    .isInstanceOf(DefaultErrorHandler.class);
            assertThat(ctx).hasSingleBean(EventConsumerMetrics.class);
        });
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("at.events.consumer.retry.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CommonErrorHandler.class));
    }

    @Test
    void aServiceDefinedErrorHandlerWins() {
        runner.withBean("custom", CommonErrorHandler.class, DefaultErrorHandler::new).run(ctx -> {
            assertThat(ctx).hasSingleBean(CommonErrorHandler.class);
            assertThat(ctx.getBean(CommonErrorHandler.class)).isNotInstanceOf(EventConsumerErrorHandler.class);
        });
    }

    @Test
    void bootsOwnKafkaTemplateIsNotDisplacedByTheDeadLetterProducer() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("kafkaTemplate");
            assertThat(ctx).hasBean("kafkaProducerFactory");
        });
    }
}
