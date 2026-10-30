package com.afrotransact.platform.events.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class DefaultEventFailureClassifierTest {

    private final EventFailureClassifier classifier = new DefaultEventFailureClassifier(List.of(), List.of());

    private static ListenerExecutionFailedException wrap(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }

    @Test
    void malformedJsonIsNotRetryableAndLabelledDeserialization() {
        Throwable jackson = jacksonFailure();
        assertThat(classifier.isRetryable(wrap(jackson))).isFalse();
        assertThat(classifier.reason(wrap(jackson))).isEqualTo("deserialization");
    }

    @Test
    void kafkaAndMessagingConversionErrorsAreNotRetryable() {
        assertThat(classifier.isRetryable(new DeserializationException("bad", new byte[0], false, null))).isFalse();
        assertThat(classifier.isRetryable(wrap(new MessageConversionException("bad")))).isFalse();
    }

    @Test
    void illegalArgumentAndExplicitMarkerAreNotRetryable() {
        assertThat(classifier.isRetryable(wrap(new IllegalArgumentException("negative amount")))).isFalse();
        assertThat(classifier.isRetryable(wrap(new NumberFormatException("x")))).isFalse();
        assertThat(classifier.isRetryable(wrap(new NonRetryableEventException("rule broken")))).isFalse();
        assertThat(classifier.reason(wrap(new NonRetryableEventException("rule broken")))).isEqualTo("non_retryable");
    }

    @Test
    void transientInfrastructureFailuresAreRetryable() {
        assertThat(classifier.isRetryable(wrap(new DataAccessResourceFailureException("db down", new SQLException())))).isTrue();
        assertThat(classifier.isRetryable(wrap(new TimeoutException("upstream")))).isTrue();
        assertThat(classifier.isRetryable(wrap(new IllegalStateException("not yet")))).isTrue();
        assertThat(classifier.reason(wrap(new TimeoutException("upstream")))).isEqualTo("retries_exhausted");
    }

    @Test
    void configuredExtraTypesAreHonouredAndUnknownOnesIgnored() {
        EventFailureClassifier custom = new DefaultEventFailureClassifier(
                List.of("java.util.concurrent.TimeoutException", "does.not.Exist", "java.lang.String"),
                List.of("java.lang.NumberFormatException"));
        assertThat(custom.isRetryable(wrap(new TimeoutException("x")))).isFalse();
        assertThat(custom.isRetryable(wrap(new NumberFormatException("x")))).isTrue();
        assertThat(custom.retryableTypes()).containsExactly(NumberFormatException.class);
        assertThat(custom.nonRetryableTypes()).contains(TimeoutException.class);
    }

    private static JacksonException jacksonFailure() {
        try {
            JsonMapper.builder().build().readTree("{not json");
            throw new AssertionError("expected a parse error");
        } catch (JacksonException ex) {
            return ex;
        }
    }
}
