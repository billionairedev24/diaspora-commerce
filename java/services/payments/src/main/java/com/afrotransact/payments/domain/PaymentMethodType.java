package com.afrotransact.payments.domain;

import java.util.Locale;
import java.util.Optional;

/** Saved payment method types offered at checkout. */
public enum PaymentMethodType {
    CARD("card"),
    US_BANK_ACCOUNT("us_bank_account"),
    CASHAPP("cashapp"),
    LINK("link");

    private final String stripe;

    PaymentMethodType(String stripe) {
        this.stripe = stripe;
    }

    /** Stripe's type code, e.g. {@code us_bank_account}. */
    public String stripe() {
        return stripe;
    }

    public static Optional<PaymentMethodType> ofStripe(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (PaymentMethodType t : values()) {
            if (t.stripe.equals(c)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
