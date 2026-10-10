package com.afrotransact.fulfilment.domain;

/** The action does not fit the current state of a route, stop, package or task (HTTP 409). */
public class FulfilmentConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public FulfilmentConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
