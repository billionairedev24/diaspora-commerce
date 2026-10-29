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
                return service.statusCode() == 404 && mentionsDataPolicy(service.getMessage() + " " + service.body())
                        ? FailureKind.PRIVACY_POLICY : FailureKind.ofStatus(service.statusCode());
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

    /** OpenRouter: "No endpoints found matching your data policy (Zero data retention)". */
    private static boolean mentionsDataPolicy(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("data policy") || m.contains("zero data retention") || m.contains("zdr");
    }
}
