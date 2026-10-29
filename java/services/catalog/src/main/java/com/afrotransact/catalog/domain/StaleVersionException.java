package com.afrotransact.catalog.domain;

/** An edit named an older version than the stored one (HTTP 412): someone else changed the record meanwhile. */
public class StaleVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleVersionException(String message) {
        super(message);
    }
}
