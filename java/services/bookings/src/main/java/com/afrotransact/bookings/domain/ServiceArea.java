package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a provider works: its base location, how far it travels (miles), named extra areas and the cities the radius
 * reaches (worked out when the area is saved, so results can say "Serves Georgetown, Hutto, Pflugerville").
 *
 * <p>Whether a service is performed at the customer's address (AT_HOME, "Comes to you") or at the provider's base
 * (IN_PERSON, "You go to them") is a property of each service ({@link ServiceMode}); the area is shared.
 */
public record ServiceArea(String orgId, String baseZip, String baseCity, String baseState, double lat, double lon,
                          int radiusMiles, List<NamedArea> extraAreas, List<ZoneCity> servedCities, Instant updatedAt) {

    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");
    public static final int MAX_EXTRA_AREAS = 20;

    public ServiceArea {
        Objects.requireNonNull(orgId, "orgId");
        Map<String, String> errors = new LinkedHashMap<>();
        if (baseZip == null || !ZIP.matcher(baseZip).matches()) {
            errors.put("baseZip", "Enter the 5-digit ZIP code of your base location");
        }
        if (baseCity == null || baseCity.isBlank() || baseState == null || baseState.trim().length() != 2) {
            errors.put("baseCity", "Choose your base city and state");
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            errors.put("baseZip", "The base location could not be placed on the map");
        }
        if (radiusMiles < 1 || radiusMiles > 150) {
            errors.put("radiusMiles", "Between 1 and 150 miles");
        }
        List<NamedArea> extras = extraAreas == null ? List.of() : extraAreas;
        if (extras.size() > MAX_EXTRA_AREAS) {
            errors.put("extraAreas", "At most " + MAX_EXTRA_AREAS + " extra areas");
        }
        if (!errors.isEmpty()) {
            throw new BookingsRuleViolation("bookings.invalid-service-area", "Service area is invalid", errors);
        }
        baseCity = baseCity.trim();
        baseState = baseState.trim().toUpperCase(Locale.ROOT);
        extraAreas = List.copyOf(extras);
        servedCities = servedCities == null ? List.of() : List.copyOf(servedCities);
    }

    public ServiceArea withRadiusAndCities(int radius, List<ZoneCity> cities, Instant now) {
        return new ServiceArea(orgId, baseZip, baseCity, baseState, lat, lon, radius, extraAreas, cities, now);
    }

    /** "Round Rock, TX". */
    public String baseLabel() {
        return baseCity + ", " + baseState;
    }
}
