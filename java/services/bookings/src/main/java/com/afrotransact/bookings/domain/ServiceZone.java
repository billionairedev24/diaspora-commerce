package com.afrotransact.bookings.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A platform-defined metro zone ("Greater Austin" = Georgetown, Round Rock, Hutto, Pflugerville, Cedar Park, ...):
 * the name results show ("Serves Greater Austin") and an area a provider can add whole.
 */
public record ServiceZone(String key, String name, List<ZoneCity> cities) {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");

    public ServiceZone {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BookingsRuleViolation("bookings.invalid-zone", "Zone key is invalid",
                    Map.of("key", "2-40 lowercase letters, digits or dashes"));
        }
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new BookingsRuleViolation("bookings.invalid-zone", "Zone name is invalid",
                    Map.of("name", "1-80 characters"));
        }
        if (cities == null || cities.isEmpty()) {
            throw new BookingsRuleViolation("bookings.invalid-zone", "A zone needs at least one city",
                    Map.of("cities", "Add at least one city"));
        }
        key = key.toLowerCase(Locale.ROOT);
        name = name.trim();
        cities = List.copyOf(cities);
    }

    public boolean contains(String city, String state) {
        return city != null && state != null && cities.stream().anyMatch(c -> c.is(city, state));
    }
}
