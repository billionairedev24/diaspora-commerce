package com.afrotransact.bookings.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Staff-set service-area rules. The default of each rule is the search radius a shopper gets until they choose their
 * own (a default, never a limit); min and max bound the radius a provider declares for how far they travel.
 *
 * @param byCategory radius rule per service category (lower case, e.g. "hair &amp; beauty")
 * @param fallback   rule for a category without its own
 */
public record AreaPolicy(Map<String, RadiusRule> byCategory, RadiusRule fallback) {

    public AreaPolicy {
        Map<String, RadiusRule> copy = new LinkedHashMap<>();
        byCategory.forEach((k, v) -> copy.put(k.trim().toLowerCase(Locale.ROOT), v));
        byCategory = Map.copyOf(copy);
    }

    public static AreaPolicy defaults() {
        Map<String, RadiusRule> m = new LinkedHashMap<>();
        m.put("hair & beauty", new RadiusRule(20, 5, 40));
        m.put("catering", new RadiusRule(40, 10, 100));
        m.put("tailoring", new RadiusRule(25, 5, 60));
        m.put("tax & legal", new RadiusRule(30, 5, 60));
        return new AreaPolicy(m, new RadiusRule(25, 5, 60));
    }

    public RadiusRule ruleFor(String category) {
        return byCategory.getOrDefault(category == null ? "" : category.trim().toLowerCase(Locale.ROOT), fallback);
    }

    /** The widest default radius of any category: the candidate bound when a search has no chosen distance. */
    public int widestDefaultMiles() {
        return Math.max(fallback.defaultMiles(), byCategory.values().stream().mapToInt(RadiusRule::defaultMiles)
                .max().orElse(0));
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
