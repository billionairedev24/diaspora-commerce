package com.afrotransact.platform.events.consumer;

/**
 * Thrown (or extended) by a listener when an event can never be processed successfully, for example a domain rule
 * that rejects it. The record goes to the dead-letter topic immediately, without retries.
 */
public class NonRetryableEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
