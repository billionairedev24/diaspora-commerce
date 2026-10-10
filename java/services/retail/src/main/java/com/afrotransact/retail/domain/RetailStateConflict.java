package com.afrotransact.retail.domain;

/** An action is not allowed in the aggregate's current state (HTTP 409), e.g. approving a draft PO. */
public class RetailStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public RetailStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
