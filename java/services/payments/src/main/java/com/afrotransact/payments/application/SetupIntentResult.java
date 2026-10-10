package com.afrotransact.payments.application;

/** Client secret for Stripe Elements (web) / PaymentSheet (mobile) to collect and save a method. */
public record SetupIntentResult(String setupIntentId, String clientSecret, String status) {
}
