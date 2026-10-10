package com.afrotransact.platform.web.error;

/** Action not allowed for this actor (HTTP 403). */
public class ForbiddenActionException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ForbiddenActionException(String code, String message) {
        super(code, message);
    }
}
