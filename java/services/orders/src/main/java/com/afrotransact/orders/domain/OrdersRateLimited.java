package com.afrotransact.orders.domain;

/** Too many requests from one shopper (HTTP 429). */
public class OrdersRateLimited extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final long retryAfterSeconds;

    public OrdersRateLimited(String code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
