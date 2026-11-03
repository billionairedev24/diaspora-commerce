package com.afrotransact.orders.application;

/** The PaymentIntent created for an order. */
public record PaymentRef(String paymentId, String clientSecret, String status, long creditMinor) {

    /** A payment without credit. */
    public PaymentRef(String paymentId, String clientSecret, String status) {
        this(paymentId, clientSecret, status, 0);
    }
}
