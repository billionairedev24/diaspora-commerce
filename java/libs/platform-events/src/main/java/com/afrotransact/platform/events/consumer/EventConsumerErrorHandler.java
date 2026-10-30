package com.afrotransact.platform.events.consumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/**
 * Spring Kafka {@link DefaultErrorHandler} with the platform policy: transient failures are retried with backoff, then
 * the record is dead-lettered; non-retryable failures are dead-lettered at once. Either way the partition moves on,
 * unless the DLQ write itself fails, in which case the record is not skipped and not committed.
 */
public class EventConsumerErrorHandler extends DefaultErrorHandler {

    private final FailureContext context;

    EventConsumerErrorHandler(EventDeadLetterRecoverer recoverer, BackOff backOff, EventFailureClassifier classifier,
                              EventRetryListener retryListener, FailureContext context) {
        super(recoverer, backOff);
        this.context = context;
        Map<Class<? extends Throwable>, Boolean> classes = new HashMap<>();
        classifier.nonRetryableTypes().forEach(type -> classes.put(type, false));
        classifier.retryableTypes().forEach(type -> classes.put(type, true));
        setClassifications(classes, true);
        setRetryListeners(retryListener);
    }

    @Override
    public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
                             MessageListenerContainer container) {
        context.enter(container.getGroupId());
        try {
            return super.handleOne(thrownException, record, consumer, container);
        } finally {
            context.exit();
        }
    }

    @Override
    public void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records,
                                Consumer<?, ?> consumer, MessageListenerContainer container) {
        context.enter(container.getGroupId());
        try {
            super.handleRemaining(thrownException, records, consumer, container);
        } finally {
            context.exit();
        }
    }

    @Override
    public void handleBatch(Exception thrownException, ConsumerRecords<?, ?> data, Consumer<?, ?> consumer,
                            MessageListenerContainer container, Runnable invokeListener) {
        context.enter(container.getGroupId());
        try {
            super.handleBatch(thrownException, data, consumer, container, invokeListener);
        } finally {
            context.exit();
        }
    }
}
