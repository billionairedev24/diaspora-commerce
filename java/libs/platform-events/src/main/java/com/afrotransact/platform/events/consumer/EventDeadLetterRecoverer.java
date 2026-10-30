package com.afrotransact.platform.events.consumer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;

/**
 * Publishes a failed record to {@code <original topic>.dlq} with the same key, value and original headers (so the
 * {@code event-id} and {@code event-type} headers survive and a replay is de-duplicated by the inbox), plus the
 * {@code x-dlq-*} diagnostic headers. The send is synchronous; if the broker does not acknowledge it this throws and
 * the container neither commits the offset nor skips the record, so it is delivered again.
 */
public class EventDeadLetterRecoverer extends DeadLetterPublishingRecoverer {

    public static final String HDR_EXCEPTION_CLASS = "x-dlq-exception-class";
    public static final String HDR_EXCEPTION_MESSAGE = "x-dlq-exception-message";
    public static final String HDR_ORIGINAL_TOPIC = "x-dlq-original-topic";
    public static final String HDR_ORIGINAL_PARTITION = "x-dlq-original-partition";
    public static final String HDR_ORIGINAL_OFFSET = "x-dlq-original-offset";
    public static final String HDR_CONSUMER_GROUP = "x-dlq-consumer-group";
    public static final String HDR_FAILED_AT = "x-dlq-failed-at";
    public static final String HDR_ATTEMPTS = "x-dlq-attempts";

    private static final String HDR_PREFIX = "x-dlq-";
    private static final Logger LOG = LoggerFactory.getLogger(EventDeadLetterRecoverer.class);

    private final EventConsumerRetryProperties properties;
    private final EventFailureClassifier classifier;
    private final FailureContext context;
    private final EventConsumerMetrics metrics;
    private final Clock clock;
    private final ThreadLocal<Exception> failing = new ThreadLocal<>();

    EventDeadLetterRecoverer(KafkaOperations<?, ?> dlqTemplate, EventConsumerRetryProperties properties,
                             EventFailureClassifier classifier, FailureContext context,
                             EventConsumerMetrics metrics, Clock clock) {
        super(dlqTemplate, (record, ex) -> new TopicPartition(record.topic() + properties.dlqSuffix(), -1));
        this.properties = properties;
        this.classifier = classifier;
        this.context = context;
        this.metrics = metrics;
        this.clock = clock;
        setVerifyPartition(false);
        setWaitForSendResultTimeout(properties.sendTimeout());
        setFailIfSendResultIsError(true);
        setThrowIfNoDestinationReturned(true);
        // Spring's own message and stack-trace headers can quote payload data; ours are sanitised.
        excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE, HeadersToAdd.EX_CAUSE);
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        String group = context.group();
        failing.set(exception);
        try {
            super.accept(record, consumer, exception);
        } catch (RuntimeException ex) {
            metrics.deadLetterFailed(group, record.topic());
            LOG.error("Dead-lettering failed; the record will be retried: consumer={} topic={} partition={} "
                            + "offset={} dlqError={}", group, record.topic(), record.partition(), record.offset(),
                    ex.getClass().getName());
            throw ex;
        } finally {
            failing.remove();
        }
        String reason = classifier.reason(exception);
        metrics.deadLettered(group, record.topic(), reason);
        LOG.warn("Event dead-lettered: consumer={} topic={} partition={} offset={} dlqTopic={} reason={} "
                        + "attempts={} exception={}", group, record.topic(), record.partition(), record.offset(),
                record.topic() + properties.dlqSuffix(), reason, context.attempts(record),
                FailureSummary.unwrap(exception).getClass().getName());
        context.forget(record);
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
            TopicPartition topicPartition, Headers headers, byte[] key, byte[] value) {
        ProducerRecord<Object, Object> out = super.createProducerRecord(record, topicPartition, headers, key, value);
        Exception exception = failing.get();
        Headers target = out.headers();
        // A record that failed before, was replayed and failed again carries x-dlq-* from the earlier round.
        List<String> stale = new ArrayList<>();
        for (Header h : target) {
            if (h.key().startsWith(HDR_PREFIX)) {
                stale.add(h.key());
            }
        }
        stale.forEach(target::remove);
        Throwable cause = exception == null ? null : FailureSummary.unwrap(exception);
        add(target, HDR_EXCEPTION_CLASS, cause == null ? "unknown" : cause.getClass().getName());
        add(target, HDR_EXCEPTION_MESSAGE, cause == null ? ""
                : FailureSummary.message(exception, properties.maxMessageLength()));
        add(target, HDR_ORIGINAL_TOPIC, record.topic());
        add(target, HDR_ORIGINAL_PARTITION, Integer.toString(record.partition()));
        add(target, HDR_ORIGINAL_OFFSET, Long.toString(record.offset()));
        add(target, HDR_CONSUMER_GROUP, context.group());
        add(target, HDR_FAILED_AT, clock.instant().toString());
        add(target, HDR_ATTEMPTS, Integer.toString(context.attempts(record)));
        return out;
    }

    private static void add(Headers headers, String name, String value) {
        headers.add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
