package com.afrotransact.bookings.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Staff-set service-area rules.
 *
 * @param byCategory         radius rule per service category (lower case, e.g. "hair &amp; beauty")
 * @param fallback           rule for a category without its own
 * @param customerTravelMiles how far a customer is assumed to travel to a provider's shop ("You go to them")
 * @param nearestMaxMiles    providers beyond their usual coverage are suggested only within this many miles
 */
public record AreaPolicy(Map<String, RadiusRule> byCategory, RadiusRule fallback, int customerTravelMiles,
                         int nearestMaxMiles) {

    public AreaPolicy {
        Map<String, RadiusRule> copy = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> copy.put(k.trim().toLowerCase(Locale.ROOT), v));
        byCategory = Map.copyOf(copy);
        if (customerTravelMiles < 1 || customerTravelMiles > 150 || nearestMaxMiles < 0 || nearestMaxMiles > 300) {
            throw new BookingsRuleViolation("bookings.invalid-area-policy", "Area policy is invalid",
                    Map.of("customerTravelMiles", "1-150 miles; nearestMaxMiles 0-300 (0 turns the fallback off)"));
        }
    }

    public static AreaPolicy defaults() {
        Map<String, RadiusRule> m = new LinkedHashMap<>();
        m.put("hair & beauty", new RadiusRule(20, 5, 40));
        m.put("catering", new RadiusRule(40, 10, 100));
        m.put("tailoring", new RadiusRule(25, 5, 60));
        m.put("tax & legal", new RadiusRule(30, 5, 60));
        return new AreaPolicy(m, new RadiusRule(25, 5, 60), 30, 60);
    }

    public RadiusRule ruleFor(String category) {
        return byCategory.getOrDefault(category == null ? "" : category.trim().toLowerCase(Locale.ROOT), fallback);
    }

    /** Bounds for a provider's one radius: widest of the categories it offers (the fallback when none). */
    public RadiusRule boundsFor(Collection<String> categories) {
        if (categories.isEmpty()) {
            return fallback;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        int def = 0;
        for (String c : categories) {
            RadiusRule r = ruleFor(c);
            min = Math.min(min, r.minMiles());
            max = Math.max(max, r.maxMiles());
            def = Math.max(def, r.defaultMiles());
        }
        return new RadiusRule(def, min, max);
    }
}
