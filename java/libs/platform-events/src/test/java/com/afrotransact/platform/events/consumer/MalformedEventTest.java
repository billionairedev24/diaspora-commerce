package com.afrotransact.platform.events.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class MalformedEventTest {

    private final EventFailureClassifier classifier = new DefaultEventFailureClassifier(List.of(), List.of());

    @Test
    void parseFailureIsNonRetryableKeepsTheCauseAndNeverQuotesThePayload() {
        JacksonException cause = catchThrowableOfType(JacksonException.class,
                () -> JsonMapper.builder().build().readTree("{\"secret\": oops"));
        NonRetryableEventException ex = MalformedEvent.of("orders.order.placed", cause);

        assertThat(ex).hasCause(cause);
        assertThat(ex.getMessage()).isEqualTo("Malformed orders.order.placed event: " + cause.getClass().getSimpleName())
                .doesNotContain("secret");
        ListenerExecutionFailedException wrapped = new ListenerExecutionFailedException("listener failed", ex);
        assertThat(classifier.isRetryable(wrapped)).isFalse();
        assertThat(FailureSummary.message(wrapped, 500)).isEqualTo(FailureSummary.REDACTED);
    }

    @Test
    void missingEventIdNamesTheRecordPositionAndIsNonRetryable() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("at.order.events", 3, 42L, "k", "{}");
        NonRetryableEventException ex = MalformedEvent.missingEventId("orders.order.placed", rec);

        assertThat(ex.getMessage()).isEqualTo(
                "Missing or invalid event-id header on orders.order.placed at at.order.events-3@42");
        assertThat(classifier.isRetryable(ex)).isFalse();
    }

    @Test
    void blankTypeIsLabelled() {
        assertThat(MalformedEvent.of("", new IllegalArgumentException("x")).getMessage())
                .startsWith("Malformed (untyped) event");
    }
}
