package com.afrotransact.orders.port;

import java.util.List;

/**
 * An upstream service refused or failed a call. {@code retryable} is true for timeouts, connection errors, 5xx and
 * 429; false for business refusals (4xx) such as {@code inventory.insufficient-stock}.
 */
public class UpstreamFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String service;
    private final int status;
    private final String code;
    private final boolean retryable;
    private final transient List<String> details;

    public UpstreamFailure(String service, int status, String code, String message, boolean retryable,
                           List<String> details, Throwable cause) {
        super(message, cause);
        this.service = service;
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String service() {
        return service;
    }

    /** HTTP status, or 0 when no response was received. */
    public int status() {
        return status;
    }

    /** Problem code of the upstream (e.g. inventory.insufficient-stock) or {@code <service>.unavailable}. */
    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Extra detail lines (e.g. per-SKU shortfalls). */
    public List<String> details() {
        return details;
    }
}
