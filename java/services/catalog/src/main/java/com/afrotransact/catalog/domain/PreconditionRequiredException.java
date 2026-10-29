package com.afrotransact.catalog.domain;

/** A change to a versioned record arrived without {@code If-Match} (HTTP 428): the caller must name what it loaded. */
public class PreconditionRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PreconditionRequiredException(String message) {
        super(message);
    }
}
