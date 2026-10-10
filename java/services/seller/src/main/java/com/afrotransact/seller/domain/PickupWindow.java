package com.afrotransact.seller.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;
import java.util.Objects;

/**
 * When an AfroTransact driver may collect packages at a pickup location: one day of the week, opening and closing
 * time (local time of the location, 24-hour). A day may have several windows (e.g. a lunch break).
 */
public record PickupWindow(DayOfWeek day, LocalTime opens, LocalTime closes) {

    public PickupWindow {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        if (!opens.isBefore(closes)) {
            throw new SellerRuleViolation("seller.invalid-pickup-hours", "Pickup hours must open before they close ("
                    + day + " " + opens + "–" + closes + ")", Map.of("hours", "opens must be before closes"));
        }
    }

    boolean overlaps(PickupWindow other) {
        return day == other.day && opens.isBefore(other.closes) && other.opens.isBefore(closes);
    }
}
