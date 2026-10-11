package com.afrotransact.content.domain.market;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * A state, district, province or nation with its capability switches and cities.
 *
 * @param country      ISO country code
 * @param code         subdivision code (TX, ON, ENG…)
 * @param name         display name
 * @param kind         subdivision kind
 * @param capabilities switches; the marketplace switch is what makes the region LIVE
 * @param zipPrefixes  postal code prefixes that belong to it (3-digit US ZIP prefixes, Canadian FSA letters)
 * @param planned      in use but not live yet (shown as PLANNED while the marketplace is off)
 * @param everyCity    every city of the region is covered; otherwise only the cities listed LIVE
 * @param cities       listed cities, each LIVE, PLANNED or OFF (with everyCity they are overrides)
 * @param version      optimistic-lock version of the draft row (0 in published snapshots)
 */
public record Region(String country, String code, String name, RegionKind kind, Capabilities capabilities,
                     List<String> zipPrefixes, boolean planned, boolean everyCity, List<City> cities, long version) {

    /** Most cities one region may list. */
    public static final int MAX_CITIES = 5000;

    public Region {
        zipPrefixes = zipPrefixes == null ? List.of() : List.copyOf(zipPrefixes);
        cities = cities == null ? List.of() : List.copyOf(cities);
    }

    /** A region without cities that covers every city (the shape before cities existed). */
    public Region(String country, String code, String name, RegionKind kind, Capabilities capabilities,
                  List<String> zipPrefixes) {
        this(country, code, name, kind, capabilities, zipPrefixes, false, true, List.of(), 0);
    }

    /** LIVE when the marketplace is on, PLANNED when marked planned, else OFF. */
    public LocationStatus status() {
        if (capabilities.marketplace()) {
            return LocationStatus.LIVE;
        }
        return planned ? LocationStatus.PLANNED : LocationStatus.OFF;
    }

    public boolean coversPostalCode(String postalCode) {
        if (postalCode == null || postalCode.isBlank()) {
            return false;
        }
        String normalised = postalCode.trim().toUpperCase(Locale.ROOT);
        return zipPrefixes.stream().anyMatch(normalised::startsWith);
    }

    public Optional<City> city(String cityName) {
        if (cityName == null || cityName.isBlank()) {
            return Optional.empty();
        }
        String k = cityName.trim().toLowerCase(Locale.ROOT);
        return cities.stream().filter(c -> c.key().equals(k)).findFirst();
    }

    public Region withCapabilities(Capabilities next) {
        return new Region(country, code, name, kind, next, zipPrefixes, planned || next.marketplace(), everyCity,
                demoteIfNotLive(next.marketplace(), cities), version);
    }

    /**
     * Changes the region's status: LIVE switches the marketplace on (other capabilities stay as they are), PLANNED
     * and OFF switch it off (which turns every capability off). Leaving LIVE turns its LIVE cities PLANNED.
     */
    public Region withStatus(LocationStatus next) {
        if (next == status()) {
            return this;
        }
        Capabilities caps = next == LocationStatus.LIVE ? capabilities.with(Capability.MARKETPLACE, true)
                : capabilities.with(Capability.MARKETPLACE, false);
        return new Region(country, code, name, kind, caps, zipPrefixes, next != LocationStatus.OFF, everyCity,
                demoteIfNotLive(next == LocationStatus.LIVE, cities), version);
    }

    public Region withEveryCity(boolean on) {
        return new Region(country, code, name, kind, capabilities, zipPrefixes, planned, on, cities, version);
    }

    /**
     * Appends cities that are not listed yet (case-insensitive); listed ones keep their status. A LIVE city needs
     * the region LIVE.
     */
    public Region addCities(Collection<City> added) {
        Map<String, City> byKey = new LinkedHashMap<>();
        cities.forEach(c -> byKey.put(c.key(), c));
        for (City c : added) {
            requireLiveRegion(c.status());
            byKey.putIfAbsent(c.key(), c);
        }
        return withCities(new ArrayList<>(byKey.values()));
    }

    /** Sets the status of the named, listed cities (unknown names are rejected). */
    public Region setCityStatus(Collection<String> names, LocationStatus next) {
        requireLiveRegion(next);
        Set<String> keys = keysOf(names);
        requireListed(keys);
        return withCities(cities.stream().map(c -> keys.contains(c.key()) ? c.withStatus(next) : c).toList());
    }

    /** Removes the named, listed cities. */
    public Region removeCities(Collection<String> names) {
        Set<String> keys = keysOf(names);
        requireListed(keys);
        return withCities(cities.stream().filter(c -> !keys.contains(c.key())).toList());
    }

    /** Counts of listed cities by status. */
    public long cityCount(LocationStatus s) {
        return cities.stream().filter(c -> c.status() == s).count();
    }

    /** Same region ignoring the draft row version (publish diffs compare content only). */
    public Region unversioned() {
        return version == 0 ? this : new Region(country, code, name, kind, capabilities, zipPrefixes, planned,
                everyCity, cities, 0);
    }

    private Region withCities(List<City> next) {
        if (next.size() > MAX_CITIES) {
            throw new ContentRuleViolation("content.too-many-cities", "A " + kind.name().toLowerCase(Locale.ROOT)
                    + " can list at most " + MAX_CITIES + " cities; cover every city instead",
                    Map.of("cities", "Too many cities"));
        }
        return new Region(country, code, name, kind, capabilities, zipPrefixes, planned, everyCity, next, version);
    }

    private void requireLiveRegion(LocationStatus cityStatus) {
        if (cityStatus == LocationStatus.LIVE && !capabilities.marketplace()) {
            throw new ContentRuleViolation("content.region-not-live", "Make " + name + " live before making its "
                    + "cities live", Map.of("status", name + " is not live"));
        }
    }

    private void requireListed(Set<String> keys) {
        Set<String> listed = cities.stream().map(City::key).collect(Collectors.toSet());
        List<String> unknown = keys.stream().filter(k -> !listed.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ContentRuleViolation("content.unknown-city", "Not listed in " + name + ": "
                    + String.join(", ", unknown), Map.of("cities", "Unknown city"));
        }
    }

    private static Set<String> keysOf(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            throw new ContentRuleViolation("content.no-cities", "Name at least one city", Map.of("cities", "Required"));
        }
        return names.stream().map(n -> n == null ? "" : n.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<City> demoteIfNotLive(boolean regionLive, List<City> cities) {
        return regionLive ? cities : cities.stream()
                .map(c -> c.status() == LocationStatus.LIVE ? c.withStatus(LocationStatus.PLANNED) : c).toList();
    }
}
