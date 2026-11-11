package com.afrotransact.bookings.domain;

import java.util.Map;

/**
 * How far the shopper is willing to go to a provider's shop, in miles. The shopper decides: there is no platform limit,
 * only a sanity bound so a request stays meaningful. Zero means "any distance".
 */
public record ShopperRadius(int miles) {

    public static final int MAX_MILES = 1_000;

    public static final ShopperRadius ANY = new ShopperRadius(0);

    public ShopperRadius {
        if (miles < 0 || miles > MAX_MILES) {
            throw new BookingsRuleViolation("bookings.invalid-radius", "Choose a search distance",
                    Map.of("radiusMiles", "0 (any distance) to " + MAX_MILES + " miles"));
        }
    }

    public boolean any() {
        return miles == 0;
    }

    /** Whether a provider {@code distance} miles away is within the shopper's reach (inclusive). */
    public boolean reaches(double distance) {
        return any() || distance <= miles;
    }
}
