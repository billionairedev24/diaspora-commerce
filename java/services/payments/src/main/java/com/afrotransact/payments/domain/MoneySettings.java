package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff-configurable money settings (Settings, Money): gift card amount limits and the terms shown at purchase, and the
 * amount above which a staff grant of credit needs a second approver. Changed without a release, each change with a
 * reason (ADR 0006).
 *
 * @param giftCardTerms           locale to terms text; empty means the built-in terms
 * @param creditApprovalLimitMinor staff grants and adjustments above this need a different approver
 */
public record MoneySettings(long giftCardMinMinor, long giftCardMaxMinor, List<Long> giftCardPresets,
                            Map<String, String> giftCardTerms, long creditApprovalLimitMinor, String updatedBy,
                            Instant updatedAt, long version) {

    public static final long ABSOLUTE_MAX_MINOR = 1_000_000L;
    static final int MAX_PRESETS = 8;
    static final int MAX_TERMS = 4000;

    public MoneySettings {
        giftCardPresets = List.copyOf(giftCardPresets);
        giftCardTerms = Map.copyOf(giftCardTerms);
        validate(giftCardMinMinor, giftCardMaxMinor, giftCardPresets, giftCardTerms, creditApprovalLimitMinor);
    }

    private static void validate(long min, long max, List<Long> presets, Map<String, String> terms, long limit) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (min < 100) {
            errors.put("giftCardMinMinor", "At least 1.00");
        }
        if (max < min) {
            errors.put("giftCardMaxMinor", "Not below the minimum");
        }
        if (max > ABSOLUTE_MAX_MINOR) {
            errors.put("giftCardMaxMinor", "At most " + ABSOLUTE_MAX_MINOR / 100 + ".00");
        }
        if (presets.size() > MAX_PRESETS || presets.stream().anyMatch(p -> p < min || p > max)) {
            errors.put("giftCardPresets", "Up to " + MAX_PRESETS + " amounts within the limits");
        }
        if (limit < 0) {
            errors.put("creditApprovalLimitMinor", "Not negative");
        }
        if (terms.values().stream().anyMatch(t -> t.length() > MAX_TERMS)) {
            errors.put("giftCardTerms", "At most " + MAX_TERMS + " characters per language");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.money-settings-invalid", "Invalid money settings", errors);
        }
    }

    /** Whether a gift card of {@code minor} may be sold or issued. */
    public boolean allowsGiftCard(long minor) {
        return minor >= giftCardMinMinor && minor <= giftCardMaxMinor;
    }

    /** Whether a staff credit change of this size needs a second approver. */
    public boolean needsApproval(long absMinor) {
        return absMinor > creditApprovalLimitMinor;
    }
}
