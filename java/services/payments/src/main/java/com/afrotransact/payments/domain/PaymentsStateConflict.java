package com.afrotransact.payments.domain;

/** The aggregate is not in a state that allows the operation (HTTP 409). */
public class PaymentsStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public PaymentsStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
