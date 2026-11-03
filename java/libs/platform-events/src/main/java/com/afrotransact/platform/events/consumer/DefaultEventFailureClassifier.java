package com.afrotransact.platform.events.consumer;

import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.ClassUtils;
import tools.jackson.core.JacksonException;

/**
 * Default policy: malformed input and argument/validation errors can never succeed, everything else is treated as
 * transient and retried with backoff (bounded by {@code max-attempts}). The cause chain is searched, so a
 * {@code ListenerExecutionFailedException} wrapping a Jackson error is classified by the Jackson error.
 */
class DefaultEventFailureClassifier implements EventFailureClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultEventFailureClassifier.class);
    private static final int MAX_CAUSE_DEPTH = 16;

    private final List<Class<? extends Throwable>> nonRetryable;
    private final List<Class<? extends Throwable>> forcedRetryable;

    DefaultEventFailureClassifier(List<String> extraNonRetryable, List<String> extraRetryable) {
        List<Class<? extends Throwable>> types = new ArrayList<>(ExceptionClassifier.defaultFatalExceptionsList());
        types.add(DeserializationException.class);
        types.add(MessageConversionException.class);
        types.add(JacksonException.class);
        types.add(IllegalArgumentException.class);
        types.add(DateTimeParseException.class);
        types.add(NonRetryableEventException.class);
        types.addAll(load(extraNonRetryable));
        this.nonRetryable = List.copyOf(types.stream().distinct().toList());
        this.forcedRetryable = List.copyOf(load(extraRetryable));
    }

    @Override
    public boolean isRetryable(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (matches(forcedRetryable, t)) {
                return true;
            }
            if (matches(nonRetryable, t)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Class<? extends Throwable>> nonRetryableTypes() {
        return nonRetryable;
    }

    @Override
    public List<Class<? extends Throwable>> retryableTypes() {
        return forcedRetryable;
    }

    @Override
    public String reason(Throwable failure) {
        if (isRetryable(failure)) {
            return "retries_exhausted";
        }
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof DeserializationException || t instanceof MessageConversionException
                    || t instanceof JacksonException) {
                return "deserialization";
            }
        }
        return "non_retryable";
    }

    private static boolean matches(List<Class<? extends Throwable>> types, Throwable t) {
        for (Class<? extends Throwable> type : types) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Throwable>> load(List<String> classNames) {
        List<Class<? extends Throwable>> loaded = new ArrayList<>();
        for (String name : classNames) {
            try {
                Class<?> c = ClassUtils.forName(name.trim(), DefaultEventFailureClassifier.class.getClassLoader());
                if (Throwable.class.isAssignableFrom(c)) {
                    loaded.add((Class<? extends Throwable>) c);
                } else {
                    LOG.warn("Ignoring {} in at.events.consumer.retry: not a Throwable", name);
                }
            } catch (ClassNotFoundException | LinkageError ex) {
                LOG.warn("Ignoring {} in at.events.consumer.retry: class not found", name);
            }
        }
        return loaded;
    }
}
