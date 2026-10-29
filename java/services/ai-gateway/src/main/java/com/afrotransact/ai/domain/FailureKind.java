package com.afrotransact.ai.domain;

/** Why a model call failed; decides between retrying the same model and falling back to the next one. */
public enum FailureKind {
    /** HTTP 429. Retried with backoff, then the next model. */
    RATE_LIMITED(true),
    /** HTTP 5xx (including OpenRouter 502/503 when an upstream provider is down). Retried, then the next model. */
    SERVER_ERROR(true),
    /** No answer within the route's timeout. Falls back immediately (retrying a slow model rarely helps). */
    TIMEOUT(false),
    /** Other 4xx such as an unknown model id or a context-length error. Falls back immediately. */
    CLIENT_ERROR(false),
    /**
     * OpenRouter found no endpoint for the model that satisfies our provider privacy preferences (for example no
     * zero-data-retention endpoint). Falls back immediately: the next candidate may have one.
     */
    PRIVACY_POLICY(false),
    /** Connection failures and anything unclassified. Falls back immediately. */
    UNKNOWN(false);

    private final boolean retryable;

    FailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Maps an HTTP status from the provider. */
    public static FailureKind ofStatus(int status) {
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status == 408) {
            return TIMEOUT;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        return status >= 400 ? CLIENT_ERROR : UNKNOWN;
    }
}
