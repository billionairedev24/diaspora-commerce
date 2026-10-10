package com.afrotransact.payments.domain;

/** Payment (Stripe PaymentIntent with manual capture) status as AfroTransact tracks it. */
public enum PaymentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    /** 3-D Secure / bank verification in progress (e.g. test card 4000 0027 6000 3184). */
    REQUIRES_ACTION,
    PROCESSING,
    /** Funds authorised (Stripe requires_capture); captured per shipment. */
    AUTHORIZED,
    PARTIALLY_CAPTURED,
    CAPTURED,
    CANCELED,
    /** Last attempt failed (e.g. declined 4000 0000 0000 9995); the shopper may retry with another method. */
    FAILED;

    public boolean isCapturable() {
        return this == AUTHORIZED || this == PARTIALLY_CAPTURED;
    }

    public boolean isFinal() {
        return this == CAPTURED || this == CANCELED;
    }

    /** Maps a Stripe PaymentIntent status. */
    public static PaymentStatus fromStripe(String status) {
        return switch (status == null ? "" : status) {
            case "requires_confirmation" -> REQUIRES_CONFIRMATION;
            case "requires_action" -> REQUIRES_ACTION;
            case "processing" -> PROCESSING;
            case "requires_capture" -> AUTHORIZED;
            case "succeeded" -> CAPTURED;
            case "canceled" -> CANCELED;
            default -> REQUIRES_PAYMENT_METHOD;
        };
    }
}
