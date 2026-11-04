package com.afrotransact.platform.events.consumer;

import java.util.List;

/** Decides whether a failed listener invocation is worth retrying. */
public interface EventFailureClassifier {

    /** {@code true} when the failure may succeed on a later attempt (database down, upstream 5xx, timeout, ...). */
    boolean isRetryable(Throwable failure);

    /** Exception types that are dead-lettered immediately. Used to configure Spring Kafka's own classifier. */
    List<Class<? extends Throwable>> nonRetryableTypes();

    /** Exception types that are always retried. Used to configure Spring Kafka's own classifier. */
    List<Class<? extends Throwable>> retryableTypes();

    /** Low-cardinality metric label: {@code deserialization}, {@code non_retryable} or {@code retries_exhausted}. */
    String reason(Throwable failure);
}
