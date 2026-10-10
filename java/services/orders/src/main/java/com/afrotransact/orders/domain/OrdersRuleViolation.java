package com.afrotransact.orders.domain;

/** An orders rule was broken by the request (HTTP 422). Carries a stable {@code orders.*} code. */
public class OrdersRuleViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public OrdersRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
