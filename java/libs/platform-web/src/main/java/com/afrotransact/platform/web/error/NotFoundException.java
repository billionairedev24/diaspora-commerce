package com.afrotransact.platform.web.error;

/** Resource not found (HTTP 404). */
public class NotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
