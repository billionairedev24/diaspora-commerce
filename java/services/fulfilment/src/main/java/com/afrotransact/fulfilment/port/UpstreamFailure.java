package com.afrotransact.fulfilment.port;

/**
 * An upstream service refused or failed a call. {@code retryable} is true for timeouts, connection errors, 5xx, 408
 * and 429; false for business refusals (4xx) such as {@code orders.handover-code-invalid}.
 */
public class UpstreamFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String service;
    private final int status;
    private final String code;
    private final boolean retryable;

    public UpstreamFailure(String service, int status, String code, String message, boolean retryable,
                           Throwable cause) {
        super(message, cause);
        this.service = service;
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public String service() {
        return service;
    }

    /** HTTP status, or 0 when no response was received. */
    public int status() {
        return status;
    }

    /** Problem code of the upstream, or {@code <service>.unavailable}. */
    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
