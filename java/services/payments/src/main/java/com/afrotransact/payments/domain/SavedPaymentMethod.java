package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A payment method saved on the shopper's Stripe Customer. Only display data is kept (brand, last 4, expiry, bank
 * name) — never full numbers.
 *
 * @param verified false while a bank account waits for microdeposit verification ("pending verification")
 */
public record SavedPaymentMethod(String id, String customerId, PaymentMethodType type, String brand, String last4,
                                 Integer expMonth, Integer expYear, String bankName, String accountType,
                                 boolean verified, Instant createdAt) {

    public SavedPaymentMethod {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        if (last4 != null && !last4.matches("\\d{4}")) {
            throw new IllegalArgumentException("last4 must be exactly four digits");
        }
    }

    /**
     * The default method cannot be removed: the shopper must choose another default first (a lone method is always
     * the default, so it stays until a new one is added and chosen).
     */
    public static void requireRemovable(String methodId, String defaultMethodId) {
        if (methodId.equals(defaultMethodId)) {
            throw new PaymentsStateConflict("payments.default-method-required",
                    "This is your default payment method; choose another default before removing it");
        }
    }
}
