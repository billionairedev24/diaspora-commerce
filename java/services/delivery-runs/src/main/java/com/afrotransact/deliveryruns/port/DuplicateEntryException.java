package com.afrotransact.deliveryruns.port;

/** A unique key (location slug, active order slot) already exists. */
public class DuplicateEntryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateEntryException(String message, Throwable cause) {
        super(message, cause);
    }
}
