package com.afrotransact.seller.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Rules for how a business's public page looks: shared by onboarding and the page settings. */
public final class BrandingRules {

    public static final int MAX_DESCRIPTION = 500;
    public static final String COLOUR_MESSAGE = "Choose a colour from the list, or enter a hex code like #1a7f4b";
    public static final String LOGO_MESSAGE = "We couldn't use this logo. Remove it and upload it again, or continue without a logo";

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
        String color = normaliseColour(in.brandColor());
        if (color == null && trim(in.brandColor()) != null) {
            errors.put("brandColor", COLOUR_MESSAGE);
        }
        String logo = trim(in.logoUrl());
        if (logo != null && !LogoUrlRules.accepts(logo)) {
            errors.put("logoUrl", LOGO_MESSAGE);
        }
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-branding", "The page details are not valid", errors);
        }
        if (description == null && color == null && logo == null) {
            return null;
        }
        return new BusinessSection.Branding(description, color, logo);
    }

    /** #rrggbb (lower case) from #rgb, rgb, #rrggbb or rrggbb in any case; null when it is none of those. */
    public static String normaliseColour(String raw) {
        String c = trim(raw);
        if (c == null) {
            return null;
        }
        if (c.startsWith("#")) {
            c = c.substring(1);
        }
        if (!c.matches("^[0-9a-fA-F]{3}$|^[0-9a-fA-F]{6}$")) {
            return null;
        }
        if (c.length() == 3) {
            c = "" + c.charAt(0) + c.charAt(0) + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2);
        }
        return "#" + c.toLowerCase(Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
