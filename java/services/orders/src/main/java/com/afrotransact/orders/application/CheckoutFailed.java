package com.afrotransact.orders.application;

import java.util.List;

/** The checkout saga ended without an order; {@code code} is a {@link CheckoutCodes} value. */
public class CheckoutFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String orderNumber;
    private final transient List<String> details;

    public CheckoutFailed(String code, String message, String orderNumber, List<String> details) {
        super(message);
        this.code = code;
        this.orderNumber = orderNumber;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String code() {
        return code;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public List<String> details() {
        return details;
    }
}
