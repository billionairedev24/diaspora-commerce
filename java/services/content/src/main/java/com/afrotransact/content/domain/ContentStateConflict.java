package com.afrotransact.content.domain;

/** The requested transition is not allowed from the current state (HTTP 409). */
public class ContentStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ContentStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
