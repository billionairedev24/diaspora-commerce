package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** A card (brand, last 4, expiry) or bank account (bank, type, last 4) charged for the plan. */
public record PaymentMethod(UUID id, String orgId, String stripePaymentMethodId, PaymentMethodKind kind,
                            String brand, String last4, Integer expMonth, Integer expYear, String bankName,
                            String accountType, boolean verified, boolean receivesPayouts, boolean isDefault,
                            Instant createdAt) {

    public PaymentMethod asDefault(boolean value) {
        return new PaymentMethod(id, orgId, stripePaymentMethodId, kind, brand, last4, expMonth, expYear, bankName,
                accountType, verified, receivesPayouts, value, createdAt);
    }

    /** "Visa ••4242" or "Chase checking ••6789". */
    public String label() {
        if (kind == PaymentMethodKind.CARD) {
            return (brand == null ? "Card" : brand.substring(0, 1).toUpperCase(Locale.ROOT)
                    + brand.substring(1)) + " ••" + last4;
        }
        return (bankName == null ? "Bank" : bankName) + (accountType == null ? "" : " " + accountType) + " ••" + last4;
    }
}
