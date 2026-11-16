package com.afrotransact.module;

import java.time.Duration;

/**
 * The called module did not answer within the edge's timeout. Callers handle it as they handle the HTTP read timeout
 * ({@code ResourceAccessException}): the retryable "{@code <service>.unavailable}" outcome, status 0 (no answer). As over HTTP, the
 * callee may still have committed: the thread is interrupted, but a transaction that already reached commit stays committed.
 */
public class ModuleCallTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String callee;
    private final transient Duration timeout;

    public ModuleCallTimeoutException(String caller, String callee, Duration timeout) {
        super("Module '" + callee + "' did not answer '" + caller + "' within " + timeout);
        this.callee = callee;
        this.timeout = timeout;
    }

    public String callee() {
        return callee;
    }

    public Duration timeout() {
        return timeout;
    }
}
