package com.afrotransact.content.domain;

/**
 * An edit named an older version than the stored one (HTTP 412 {@code content.version-mismatch}): someone else
 * changed the record since it was loaded. Carries the current version to reload with.
 */
public class StaleVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long current;

    public StaleVersionException(String message, long current) {
        super(message);
        this.current = current;
    }

    public long current() {
        return current;
    }

    /** Throws when {@code expected} is given and differs from {@code current}. */
    public static void check(Long expected, long current, String what) {
        if (expected != null && expected != current) {
            throw new StaleVersionException(what + " was changed by someone else. Refresh to see the latest.", current);
        }
    }
}
