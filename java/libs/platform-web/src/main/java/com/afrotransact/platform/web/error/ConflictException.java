package com.afrotransact.platform.web.error;

/** State conflict (HTTP 409). */
public class ConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
