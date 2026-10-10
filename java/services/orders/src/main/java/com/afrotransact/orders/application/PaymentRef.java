package com.afrotransact.orders.application;

/** The PaymentIntent created for an order. */
public record PaymentRef(String paymentId, String clientSecret, String status) {
}
