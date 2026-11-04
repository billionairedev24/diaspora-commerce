package com.afrotransact.platform.events.consumer;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

/**
 * Registers one {@link CommonErrorHandler} that Spring Boot applies to every {@code @KafkaListener} container:
 * exponential backoff with jitter, a dead-letter topic per source topic and metrics. Opt out with
 * {@code at.events.consumer.retry.enabled=false}, or declare your own {@link CommonErrorHandler} bean.
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({ConcurrentKafkaListenerContainerFactory.class, KafkaProperties.class})
@ConditionalOnBean(KafkaProperties.class)
@ConditionalOnProperty(prefix = "at.events.consumer.retry", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(EventConsumerRetryProperties.class)
public class EventConsumerErrorHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EventFailureClassifier eventFailureClassifier(EventConsumerRetryProperties properties) {
        return new DefaultEventFailureClassifier(properties.nonRetryableExceptions(),
                properties.retryableExceptions());
    }

    @Bean
    @ConditionalOnMissingBean
    EventConsumerMetrics eventConsumerMetrics(ObjectProvider<MeterRegistry> registry) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return new NoopEventConsumerMetrics();
        }
        return new MicrometerEventConsumerMetrics(meters);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DeadLetterProducer eventDeadLetterProducer(KafkaProperties kafka) {
        Map<String, Object> config = new HashMap<>(kafka.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, PassThroughSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, PassThroughSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        DefaultKafkaProducerFactory<Object, Object> factory = new DefaultKafkaProducerFactory<>(config);
        return new DeadLetterProducer(factory, new KafkaTemplate<>(factory));
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler eventConsumerErrorHandler(EventConsumerRetryProperties properties,
                                                 EventFailureClassifier classifier, EventConsumerMetrics metrics,
                                                 DeadLetterProducer producer, ObjectProvider<Clock> clock) {
        FailureContext context = new FailureContext();
        EventDeadLetterRecoverer recoverer = new EventDeadLetterRecoverer(producer.template(), properties, classifier,
                context, metrics, clock.getIfAvailable(Clock::systemUTC));
        EventRetryListener retryListener = new EventRetryListener(properties.maxAttempts(), classifier, context,
                metrics);
        return new EventConsumerErrorHandler(recoverer, backOff(properties), classifier, retryListener, context);
    }

    static BackOff backOff(EventConsumerRetryProperties properties) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxAttempts() - 1);
        backOff.setInitialInterval(properties.initialInterval().toMillis());
        backOff.setMultiplier(properties.multiplier());
        backOff.setMaxInterval(properties.maxInterval().toMillis());
        backOff.setJitter(properties.jitter().toMillis());
        return backOff;
    }

    /** Used when the application has no Micrometer registry. */
    static final class NoopEventConsumerMetrics implements EventConsumerMetrics {

        @Override
        public void retried(String consumer, String topic) {
        }

        @Override
        public void deadLettered(String consumer, String topic, String reason) {
        }

        @Override
        public void deadLetterFailed(String consumer, String topic) {
        }
    }

    /** Keeps the DLQ producer out of the bean set so Boot's own {@code KafkaTemplate} is not displaced. */
    static final class DeadLetterProducer {

        private final DefaultKafkaProducerFactory<Object, Object> factory;
        private final KafkaTemplate<Object, Object> template;

        DeadLetterProducer(DefaultKafkaProducerFactory<Object, Object> factory, KafkaTemplate<Object, Object> template) {
            this.factory = factory;
            this.template = template;
        }

        KafkaTemplate<Object, Object> template() {
            return template;
        }

        void close() {
            factory.destroy();
        }
    }
}
