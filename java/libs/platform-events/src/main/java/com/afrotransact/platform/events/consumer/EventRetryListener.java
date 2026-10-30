package com.afrotransact.platform.events.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

/** Counts and logs every failed delivery that will be tried again. Never touches the record value. */
class EventRetryListener implements RetryListener {

    private static final Logger LOG = LoggerFactory.getLogger(EventRetryListener.class);

    private final int maxAttempts;
    private final EventFailureClassifier classifier;
    private final FailureContext context;
    private final EventConsumerMetrics metrics;

    EventRetryListener(int maxAttempts, EventFailureClassifier classifier, FailureContext context,
                       EventConsumerMetrics metrics) {
        this.maxAttempts = maxAttempts;
        this.classifier = classifier;
        this.context = context;
        this.metrics = metrics;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
        context.attempt(record, deliveryAttempt);
        if (deliveryAttempt < maxAttempts && classifier.isRetryable(exception)) {
            String group = context.group();
            metrics.retried(group, record.topic());
            LOG.warn("Event processing failed, will retry: consumer={} topic={} partition={} offset={} attempt={}/{} "
                            + "exception={}", group, record.topic(), record.partition(), record.offset(),
                    deliveryAttempt, maxAttempts, FailureSummary.unwrap(exception).getClass().getName());
        }
    }
}
