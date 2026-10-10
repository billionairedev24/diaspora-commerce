package com.afrotransact.orders.domain;

/** The order or shipment is not in a state that allows the request (HTTP 409). */
public class OrdersStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public OrdersStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
