package com.afrotransact.platform.web.error;

/** Business rule violated (HTTP 422). */
public class BusinessRuleException extends DomainException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}
