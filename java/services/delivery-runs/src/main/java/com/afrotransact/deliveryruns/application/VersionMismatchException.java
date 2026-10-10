package com.afrotransact.deliveryruns.application;

/** {@code If-Match} named an older version than the stored one (HTTP 412 {@code delivery-runs.version-mismatch}). */
public class VersionMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long current;

    public VersionMismatchException(String message, long current) {
        super(message);
        this.current = current;
    }

    /** The stored version (the ETag to reload with). */
    public long current() {
        return current;
    }
}
