package com.afrotransact.fulfilment.domain;

/** A business rule of fulfilment or delivery was violated (HTTP 422). {@code code} is stable, {@code fulfilment.*}. */
public class FulfilmentRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public FulfilmentRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
