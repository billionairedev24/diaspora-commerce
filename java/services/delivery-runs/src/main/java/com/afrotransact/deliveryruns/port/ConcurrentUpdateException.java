package com.afrotransact.deliveryruns.port;

/** Optimistic lock failure: the row changed since it was read. */
public class ConcurrentUpdateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentUpdateException(String message) {
        super(message);
    }
}
