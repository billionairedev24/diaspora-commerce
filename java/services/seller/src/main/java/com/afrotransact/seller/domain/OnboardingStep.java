package com.afrotransact.seller.domain;

import java.util.Arrays;
import java.util.Locale;

/** Steps of the onboarding wizard in display order; {@link #key()} is the URL segment. */
public enum OnboardingStep {
    BUSINESS("business"),
    BUSINESS_DETAILS("business-details"),
    OWNERS("owners"),
    STRIPE_VERIFICATION("stripe-verification"),
    PAYOUTS("payouts"),
    PLAN("plan"),
    CATALOG_COMPLIANCE("catalog-compliance"),
    REVIEW("review");

    private final String key;

    OnboardingStep(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static OnboardingStep fromKey(String raw) {
        String k = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(s -> s.key.equals(k)).findFirst()
                .orElseThrow(() -> new SellerRuleViolation("seller.unknown-step", "Unknown onboarding step " + raw));
    }
}
