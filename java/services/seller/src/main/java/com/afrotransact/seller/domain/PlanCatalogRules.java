package com.afrotransact.seller.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Validation of staff plan edits; field errors are reported together (422 seller.invalid-plan). */
public final class PlanCatalogRules {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{2,39}");
    private static final Pattern LIMIT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final int MAX_FEATURES = 12;
    private static final int MAX_LIMITS = 20;

    private PlanCatalogRules() {
    }

    public static void validate(PlanInput in, boolean creating) {
        Map<String, String> e = new LinkedHashMap<>();
        if (creating) {
            if (in.code() == null || !CODE.matcher(in.code()).matches()) {
                e.put("code", "3–40 lower-case letters, digits or _ (starts with a letter)");
            }
            if (in.kind() == null) {
                e.put("kind", "Seller or provider plan?");
            }
        }
        if (in.name() == null || in.name().isBlank() || in.name().length() > 80) {
            e.put("name", "A name of up to 80 characters");
        }
        if (in.tagline() != null && in.tagline().length() > 120) {
            e.put("tagline", "Up to 120 characters");
        }
        if (in.features().size() > MAX_FEATURES || in.features().stream().anyMatch(f -> f == null || f.isBlank()
                || f.length() > 120)) {
            e.put("features", "Up to " + MAX_FEATURES + " features of up to 120 characters");
        }
        if (in.sortOrder() != null && (in.sortOrder() < 0 || in.sortOrder() > 10_000)) {
            e.put("sortOrder", "0 to 10000");
        }
        if (in.limits().size() > MAX_LIMITS || in.limits().entrySet().stream().anyMatch(l ->
                l.getKey() == null || !LIMIT_KEY.matcher(l.getKey()).matches() || l.getValue() == null
                || l.getValue().length() > 120)) {
            e.put("limits", "Up to " + MAX_LIMITS + " settings with letter-and-digit names and values of up to 120 "
                    + "characters");
        } else {
            e.putAll(PlanEntitlements.validate(in.limits()));
        }
        throwIfAny(e);
    }

    public static void validate(PlanPricesInput in) {
        Map<String, String> e = new LinkedHashMap<>();
        price(e, "monthlyCents", in.monthlyCents());
        price(e, "quarterlyCents", in.quarterlyCents());
        price(e, "annualCents", in.annualCents());
        if (in.trialDays() == null || in.trialDays() < 0 || in.trialDays() > 365) {
            e.put("trialDays", "0 to 365 days");
        }
        if (e.isEmpty()) {
            boolean anyFree = in.monthlyCents() == 0 || in.quarterlyCents() == 0 || in.annualCents() == 0;
            boolean allFree = in.monthlyCents() == 0 && in.quarterlyCents() == 0 && in.annualCents() == 0;
            if (anyFree && !allFree) {
                e.put("monthlyCents", "A free plan is free on every cadence; a paid plan costs something on each");
            }
            if (in.quarterlyCents() > in.monthlyCents() * 3) {
                e.put("quarterlyCents", "Quarterly can't cost more than 3 months");
            }
            if (in.annualCents() > in.monthlyCents() * 12) {
                e.put("annualCents", "Annual can't cost more than 12 months");
            }
            if (allFree && in.trialDays() > 0) {
                e.put("trialDays", "A free plan has no trial");
            }
        }
        throwIfAny(e);
    }

    private static void price(Map<String, String> e, String field, Long cents) {
        if (cents == null || cents < 0 || cents > 10_000_000) {
            e.put(field, "0 to $100,000 in cents");
        }
    }

    private static void throwIfAny(Map<String, String> e) {
        if (!e.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-plan", "Check the plan: " + String.join(", ", e.keySet()),
                    e);
        }
    }
}
