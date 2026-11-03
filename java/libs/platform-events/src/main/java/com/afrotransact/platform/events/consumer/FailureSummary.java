package com.afrotransact.platform.events.consumer;

import java.util.regex.Pattern;

import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import tools.jackson.core.JacksonException;

/**
 * Builds the exception class and text written to the {@code x-dlq-exception-*} headers. This is best effort and errs
 * on the side of saying less: parser and conversion errors quote the payload (Jackson prints a source snippet), so
 * for those only a fixed marker is reported. Other messages lose any {@code [Source: ...]} or {@code Detail:}
 * section, quoted and parenthesised values (database constraint errors print the offending row) and newlines, and
 * are truncated.
 */
final class FailureSummary {

    static final String REDACTED = "<payload parse error; message withheld>";

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final Pattern SOURCE = Pattern.compile("\\[Source:.*", Pattern.DOTALL);
    private static final Pattern DETAIL = Pattern.compile("(?i)detail:.*", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("(['\"`]).{0,500}?\\1", Pattern.DOTALL);
    private static final Pattern PARENTHESISED = Pattern.compile("\\([^()]{0,500}\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private FailureSummary() {
    }

    /** The exception the listener really threw: Spring Kafka's wrapper is removed. */
    static Throwable unwrap(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t instanceof ListenerExecutionFailedException && t.getCause() != null
                && depth < MAX_CAUSE_DEPTH; depth++) {
            t = t.getCause();
        }
        return t;
    }

    static String message(Throwable failure, int maxLength) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (isPayloadQuoting(t)) {
                return REDACTED;
            }
        }
        String raw = unwrap(failure).getMessage();
        if (raw == null) {
            return "";
        }
        String text = SOURCE.matcher(raw).replaceAll("");
        text = DETAIL.matcher(text).replaceAll("");
        text = QUOTED.matcher(text).replaceAll("$1...$1");
        text = PARENTHESISED.matcher(text).replaceAll("(...)");
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private static boolean isPayloadQuoting(Throwable t) {
        return t instanceof JacksonException || t instanceof DeserializationException
                || t instanceof MessageConversionException;
    }
}
