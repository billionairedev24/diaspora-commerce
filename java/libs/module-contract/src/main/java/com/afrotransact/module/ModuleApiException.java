package com.afrotransact.module;

import java.util.List;

/**
 * A refusal or failure of an in-process module API call, carrying what the HTTP endpoint would have answered: the status (403
 * scope missing, 404, 409, 422, 400, 5xx), the stable problem {@code code} and the detail. A called module translates its own
 * domain errors into this one type (the mapping its {@code @RestControllerAdvice} applies over HTTP), so the calling module
 * handles the same outcomes in both modes without importing the other module's exception classes.
 */
public class ModuleApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final List<String> details;

    public ModuleApiException(int status, String code, String detail) {
        this(status, code, detail, List.of(), null);
    }

    public ModuleApiException(int status, String code, String detail, List<String> details, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /** The HTTP status the endpoint would have answered. */
    public int status() {
        return status;
    }

    /** The problem {@code code}, for example {@code inventory.insufficient-stock}. */
    public String code() {
        return code;
    }

    /** Per-item detail lines (for example stock shortfalls); empty when there are none. */
    public List<String> details() {
        return details;
    }

    /** True for the statuses a client retries over HTTP: 5xx, 408 and 429. */
    public boolean retryable() {
        return status >= 500 || status == 408 || status == 429;
    }
}
