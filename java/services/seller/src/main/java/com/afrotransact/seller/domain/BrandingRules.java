package com.afrotransact.seller.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Rules for how a business's public page looks: shared by onboarding and the page settings. */
public final class BrandingRules {

    public static final int MAX_DESCRIPTION = 500;

    private BrandingRules() {
    }

    /** The cleaned branding (null when nothing was given); throws {@code seller.invalid-branding} with field errors. */
    public static BusinessSection.Branding clean(BusinessSection.Branding in) {
        if (in == null) {
            return null;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String description = trim(in.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            errors.put("description", "At most " + MAX_DESCRIPTION + " characters");
        }
        String color = trim(in.brandColor());
        if (color != null && !color.matches("^#[0-9a-fA-F]{6}$")) {
            errors.put("brandColor", "Use a colour like #1a7f4b");
        }
        String logo = trim(in.logoUrl());
        if (logo != null && !logo.matches("^https://\\S+$")) {
            errors.put("logoUrl", "Upload your logo again");
        }
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-branding", "The page details are not valid", errors);
        }
        if (description == null && color == null && logo == null) {
            return null;
        }
        return new BusinessSection.Branding(description, color == null ? null : color.toLowerCase(Locale.ROOT), logo);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
