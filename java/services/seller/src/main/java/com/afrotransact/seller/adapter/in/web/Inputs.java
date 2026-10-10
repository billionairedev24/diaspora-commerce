package com.afrotransact.seller.adapter.in.web;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.SellerRuleViolation;

/** Lenient parsing of enum-like request values ({@code sole_proprietor}, {@code quarterly}, {@code card} …). */
final class Inputs {

    private Inputs() {
    }

    static EntityType entityType(String raw) {
        return parse(raw, "business.entityType", () -> EntityType.parse(raw));
    }

    static Offering offering(String raw) {
        return parse(raw, "business.offering", () -> Offering.valueOf(norm(raw)));
    }

    static Cadence cadence(String raw) {
        return parse(raw, "cadence", () -> Cadence.parse(raw));
    }

    static OrgKind kind(String raw) {
        return parse(raw, "kind", () -> OrgKind.valueOf(norm(raw)));
    }

    static PaymentMethodKind paymentMethodKind(String raw) {
        return parse(raw, "paymentMethodKind", () -> switch (norm(raw)) {
            case "ACH", "BANK", "US_BANK_ACCOUNT", "ACH_PAYOUT_BANK" -> PaymentMethodKind.US_BANK_ACCOUNT;
            default -> PaymentMethodKind.valueOf(norm(raw));
        });
    }

    private static String norm(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static <T> T parse(String raw, String field, Supplier<T> parser) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Unknown value '" + raw + "' for " + field,
                    Map.of(field, "Unknown value"));
        }
    }
}
