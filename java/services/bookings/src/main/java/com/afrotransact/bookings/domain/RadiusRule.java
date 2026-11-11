package com.afrotransact.bookings.domain;

import java.util.Map;

/** Default, minimum and maximum service radius in miles (staff-set, per service category). */
public record RadiusRule(int defaultMiles, int minMiles, int maxMiles) {

    public RadiusRule {
        if (minMiles < 1 || maxMiles < minMiles || maxMiles > 150 || defaultMiles < minMiles
                || defaultMiles > maxMiles) {
            throw new BookingsRuleViolation("bookings.invalid-area-policy", "Radius rule is invalid",
                    Map.of("radius", "Need 1 <= min <= default <= max <= 150 miles"));
        }
    }

    public int clamp(int miles) {
        return Math.clamp(miles, minMiles, maxMiles);
    }
}
