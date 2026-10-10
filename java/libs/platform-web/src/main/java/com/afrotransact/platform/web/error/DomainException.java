package com.afrotransact.platform.web.error;

/**
 * Base type for typed domain errors. Services throw subclasses from the application layer;
 * {@link ProblemDetailsAdvice} maps them to RFC 9457 problem+json at the edge.
 */
public abstract class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable machine-readable code, for example {@code catalog.product-not-found}. */
    public String code() {
        return code;
    }
}
