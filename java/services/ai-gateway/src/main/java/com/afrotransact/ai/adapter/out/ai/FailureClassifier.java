package com.afrotransact.ai.adapter.out.ai;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import com.afrotransact.ai.domain.FailureKind;
import com.openai.errors.OpenAIServiceException;

/** Maps OpenAI-SDK, OkHttp and Reactor failures to {@link FailureKind}. */
final class FailureClassifier {

    private FailureClassifier() {
    }

    static FailureKind classify(Throwable failure) {
        Throwable t = failure;
        int depth = 0;
        while (t != null && depth++ < 10) {
            if (t instanceof OpenAIServiceException service) {
                return FailureKind.ofStatus(service.statusCode());
            }
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                return FailureKind.TIMEOUT;
            }
            if (t instanceof ConnectException) {
                return FailureKind.UNKNOWN;
            }
            if (t instanceof InterruptedIOException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("timeout")) {
                return FailureKind.TIMEOUT;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return FailureKind.UNKNOWN;
    }
}
