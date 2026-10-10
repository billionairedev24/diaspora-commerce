package com.afrotransact.payments.domain;

/** A domain rule denies the action to this requester (HTTP 403), e.g. a seller member who is not owner/finance. */
public class PaymentsForbidden extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public PaymentsForbidden(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
