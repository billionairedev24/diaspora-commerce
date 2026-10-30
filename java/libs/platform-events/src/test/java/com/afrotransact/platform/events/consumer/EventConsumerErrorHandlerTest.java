package com.afrotransact.platform.events.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Drives the real handler, recoverer and backoff against a mocked consumer, container and DLQ template. */
@SuppressWarnings({"unchecked", "rawtypes"})
class EventConsumerErrorHandlerTest {

    private static final String GROUP = "payments-service";
    private static final String TOPIC = "at.order.events";

    private static final EventConsumerRetryProperties PROPS = new EventConsumerRetryProperties(true,
            Duration.ofMillis(1), 2.0, Duration.ofMillis(4), Duration.ZERO, 6, ".dlq", Duration.ofSeconds(1), 200,
            List.of(), List.of());

    private SimpleMeterRegistry meters;
    private KafkaTemplate<Object, Object> dlqTemplate;
    private Consumer<Object, Object> consumer;
    private MessageListenerContainer container;
    private EventConsumerErrorHandler handler;
    private ConsumerRecord<Object, Object> record;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meters = new SimpleMeterRegistry();
        dlqTemplate = mock(KafkaTemplate.class);
        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class);
        when(container.getGroupId()).thenReturn(GROUP);
        when(container.isRunning()).thenReturn(true);
        ContainerProperties containerProperties = new ContainerProperties(TOPIC);
        containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        succeedSends();
        handler = buildHandler(PROPS);
        record = new ConsumerRecord<>(TOPIC, 2, 41L, "order-7", "{\"id\":\"o7\",\"email\":\"secret@example.com\"}");
        record.headers().add(new RecordHeader("event-id", "11111111-1111-1111-1111-111111111111".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event-type", "order.created".getBytes(StandardCharsets.UTF_8)));
    }

    private EventConsumerErrorHandler buildHandler(EventConsumerRetryProperties props) {
        EventFailureClassifier classifier = new DefaultEventFailureClassifier(props.nonRetryableExceptions(),
                props.retryableExceptions());
        EventConsumerMetrics metrics = new MicrometerEventConsumerMetrics(meters);
        FailureContext context = new FailureContext();
        Clock clock = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC);
        EventDeadLetterRecoverer recoverer = new EventDeadLetterRecoverer(dlqTemplate, props, classifier, context,
                metrics, clock);
        EventRetryListener listener = new EventRetryListener(props.maxAttempts(), classifier, context, metrics);
        return new EventConsumerErrorHandler(recoverer, EventConsumerErrorHandlingAutoConfiguration.backOff(props),
                classifier, listener, context);
    }

    @SuppressWarnings("unchecked")
    private void succeedSends() {
        SendResult<Object, Object> result = mock(SendResult.class);
        doReturn(CompletableFuture.completedFuture(result)).when(dlqTemplate).send(any(ProducerRecord.class));
    }

    private void fail(Exception cause) {
        handler.handleRemaining(new ListenerExecutionFailedException("listener failed", cause), new ArrayList<>(List.of(record)),
                consumer, container);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<Object, Object> sentToDlq() {
        ArgumentCaptor<ProducerRecord<Object, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(dlqTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static String header(ProducerRecord<Object, Object> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void nonRetryableFailureGoesToDlqAtOnceWithoutRetries() {
        fail(new IllegalArgumentException("amount must be positive"));

        ProducerRecord<Object, Object> sent = sentToDlq();
        assertThat(sent.topic()).isEqualTo("at.order.events.dlq");
        assertThat(sent.partition()).isNull();
        assertThat(sent.key()).isEqualTo("order-7");
        assertThat(sent.value()).isEqualTo(record.value());
        assertThat(header(sent, "x-dlq-attempts")).isEqualTo("1");
        assertThat(count("events.consumer.retries")).isZero();
        assertThat(count("events.dead.lettered", "consumer", GROUP, "topic", TOPIC, "reason", "non_retryable"))
                .isEqualTo(1);
    }

    @Test
    void deadLetterRecordKeepsOriginalHeadersAndAddsDiagnostics() {
        fail(new IllegalArgumentException("amount must be positive"));

        ProducerRecord<Object, Object> sent = sentToDlq();
        assertThat(header(sent, "event-id")).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(header(sent, "event-type")).isEqualTo("order.created");
        assertThat(header(sent, "x-dlq-exception-class")).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(header(sent, "x-dlq-exception-message")).isEqualTo("amount must be positive");
        assertThat(header(sent, "x-dlq-original-topic")).isEqualTo(TOPIC);
        assertThat(header(sent, "x-dlq-original-partition")).isEqualTo("2");
        assertThat(header(sent, "x-dlq-original-offset")).isEqualTo("41");
        assertThat(header(sent, "x-dlq-consumer-group")).isEqualTo(GROUP);
        assertThat(header(sent, "x-dlq-failed-at")).isEqualTo("2026-03-04T05:06:07Z");
        // Spring's own exception message / stack trace headers can quote the payload and are not sent.
        assertThat(sent.headers().lastHeader("kafka_dlt-exception-message")).isNull();
        assertThat(sent.headers().lastHeader("kafka_dlt-exception-stacktrace")).isNull();
    }

    @Test
    void headersOfEarlierDeadLetterRoundsAreReplacedNotDuplicated() {
        record.headers().add(new RecordHeader("x-dlq-original-offset", "5".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("x-replayed-by", "ops".getBytes(StandardCharsets.UTF_8)));

        fail(new IllegalArgumentException("still bad"));

        ProducerRecord<Object, Object> sent = sentToDlq();
        assertThat(sent.headers().headers("x-dlq-original-offset")).hasSize(1);
        assertThat(header(sent, "x-dlq-original-offset")).isEqualTo("41");
        assertThat(header(sent, "x-replayed-by")).isEqualTo("ops");
    }

    @Test
    void malformedJsonMessageNeverReachesHeadersOrMetricsLabels() {
        try {
            JsonMapper.builder().build().readTree("{\"email\":\"secret@example.com\" x");
        } catch (JacksonException ex) {
            fail(ex);
        }

        ProducerRecord<Object, Object> sent = sentToDlq();
        assertThat(header(sent, "x-dlq-exception-message")).isEqualTo(FailureSummary.REDACTED);
        assertThat(count("events.dead.lettered", "reason", "deserialization")).isEqualTo(1);
    }

    @Test
    void transientFailureIsRetriedWithBackoffThenDeadLettered() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> fail(new TimeoutException("upstream"))).hasMessageContaining("Record in retry");
            verify(dlqTemplate, never()).send(any(ProducerRecord.class));
        }
        // Each failed attempt above threw: the container then does not acknowledge the record.
        // Every retry rewinds the partition to the failed record, so it is redelivered and nothing is committed.
        verify(consumer, times(5)).seek(new TopicPartition(TOPIC, 2), 41L);
        assertThat(count("events.consumer.retries", "consumer", GROUP, "topic", TOPIC)).isEqualTo(5);

        fail(new TimeoutException("upstream"));

        ProducerRecord<Object, Object> sent = sentToDlq();
        assertThat(header(sent, "x-dlq-attempts")).isEqualTo("6");
        assertThat(header(sent, "x-dlq-exception-class")).isEqualTo("java.util.concurrent.TimeoutException");
        assertThat(count("events.consumer.retries")).isEqualTo(5);
        assertThat(count("events.dead.lettered", "reason", "retries_exhausted")).isEqualTo(1);
    }

    @Test
    void failedDlqWriteDoesNotCommitAndTheRecordIsRetried() {
        CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker unavailable"));
        doReturn(failed).when(dlqTemplate).send(any(ProducerRecord.class));

        assertThatThrownBy(() -> fail(new IllegalArgumentException("poison")))
                .hasMessageContaining("Record in retry");

        verify(consumer).seek(new TopicPartition(TOPIC, 2), 41L);
        assertThat(count("events.dead.letter.failures", "consumer", GROUP, "topic", TOPIC)).isEqualTo(1);
        assertThat(count("events.dead.lettered")).isZero();

        // Broker is back: the redelivered record is dead-lettered and the handler returns, so the container acks it.
        succeedSends();
        fail(new IllegalArgumentException("poison"));
        assertThat(count("events.dead.lettered", "reason", "non_retryable")).isEqualTo(1);
    }

    @Test
    void singleAttemptConfigurationDeadLettersImmediately() {
        EventConsumerRetryProperties once = new EventConsumerRetryProperties(true, Duration.ofMillis(1), 2.0,
                Duration.ofMillis(4), Duration.ZERO, 1, ".dlq", Duration.ofSeconds(1), 200, List.of(), List.of());
        handler = buildHandler(once);

        fail(new TimeoutException("upstream"));

        assertThat(header(sentToDlq(), "x-dlq-attempts")).isEqualTo("1");
        assertThat(count("events.consumer.retries")).isZero();
    }
}
