package com.afrotransact.platform.events.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class FailureSummaryTest {

    @Test
    void parseErrorsNeverQuoteThePayload() {
        JacksonException ex = null;
        try {
            JsonMapper.builder().build().readTree("{\"email\":\"secret@example.com\" oops");
        } catch (JacksonException caught) {
            ex = caught;
        }
        assertThat(ex).isNotNull();
        String text = FailureSummary.message(new ListenerExecutionFailedException("x", ex), 200);
        assertThat(text).isEqualTo(FailureSummary.REDACTED).doesNotContain("secret");
    }

    @Test
    void constraintStyleMessagesLoseRowValues() {
        Exception ex = new IllegalStateException(
                "duplicate key value violates constraint \"uq_email\"\n  Detail: Key (email)=(secret@example.com) exists");
        String text = FailureSummary.message(ex, 200);
        assertThat(text).doesNotContain("secret").doesNotContain("\n").contains("duplicate key value");
    }

    @Test
    void quotedAndParenthesisedValuesAreMasked() {
        String text = FailureSummary.message(new IllegalStateException("cannot find 'ada@example.com' (id=42)"), 200);
        assertThat(text).isEqualTo("cannot find '...' (...)");
    }

    @Test
    void longMessagesAreTruncated() {
        String text = FailureSummary.message(new IllegalStateException("x".repeat(500)), 50);
        assertThat(text).hasSize(53).endsWith("...");
    }

    @Test
    void wrapperIsRemovedButCausesAreKept() {
        IllegalStateException inner = new IllegalStateException("boom");
        assertThat(FailureSummary.unwrap(new ListenerExecutionFailedException("x", inner))).isSameAs(inner);
        assertThat(FailureSummary.message(new ListenerExecutionFailedException("x", inner), 50)).isEqualTo("boom");
    }

    @Test
    void nullMessageGivesEmptyText() {
        assertThat(FailureSummary.message(new IllegalStateException(), 50)).isEmpty();
    }
}
