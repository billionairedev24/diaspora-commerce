package com.afrotransact.platform.events.consumer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * State shared by the error handler, its retry listener and the dead-letter recoverer for one handler instance:
 * the consumer group of the container currently handling a failure (the retry listener is not told) and the delivery
 * attempt reached by the record failing on each partition. A partition blocks on its failing record, so one slot per
 * partition is enough and the map cannot grow beyond the number of partitions.
 */
final class FailureContext {

    private static final String UNKNOWN_GROUP = "unknown";

    private record Attempt(long offset, int count) {
    }

    private final ThreadLocal<String> group = new ThreadLocal<>();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    void enter(String groupId) {
        group.set(groupId == null ? UNKNOWN_GROUP : groupId);
    }

    void exit() {
        group.remove();
    }

    String group() {
        String g = group.get();
        return g == null ? UNKNOWN_GROUP : g;
    }

    void attempt(ConsumerRecord<?, ?> record, int attempt) {
        attempts.put(slot(record), new Attempt(record.offset(), attempt));
    }

    int attempts(ConsumerRecord<?, ?> record) {
        Attempt a = attempts.get(slot(record));
        return a != null && a.offset() == record.offset() ? a.count() : 1;
    }

    void forget(ConsumerRecord<?, ?> record) {
        attempts.remove(slot(record));
    }

    private static String slot(ConsumerRecord<?, ?> record) {
        return record.topic() + '-' + record.partition();
    }
}
