package com.afrotransact.deliveryruns.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A pre-order place: {@code country/region} (the whole region) or {@code country/region/city}. Country is ISO-3166
 * alpha-2, region the subdivision code (e.g. {@code TX}, {@code ON}, {@code ENG}); city matching is case-insensitive.
 *
 * @param country ISO-3166 alpha-2, upper case
 * @param region  subdivision code, upper case
 * @param city    city name as entered, or null for the whole region
 */
public record AreaKey(String country, String region, String city) {

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern REGION = Pattern.compile("^[A-Z0-9]{1,3}$");

    public AreaKey {
        country = country == null ? null : country.trim().toUpperCase(Locale.ROOT);
        region = region == null ? null : region.trim().toUpperCase(Locale.ROOT);
        city = city == null || city.isBlank() ? null : city.trim().replaceAll("\\s+", " ");
        if (country == null || !COUNTRY.matcher(country).matches()) {
            throw new RuleViolation("delivery-runs.invalid-area", "Country must be an ISO-3166 alpha-2 code");
        }
        if (region == null || !REGION.matcher(region).matches()) {
            throw new RuleViolation("delivery-runs.invalid-area", "Region must be a subdivision code, e.g. TX");
        }
        if (city != null && (city.length() > 80 || city.contains("/"))) {
            throw new RuleViolation("delivery-runs.invalid-area", "City name is at most 80 characters, without '/'");
        }
    }

    /** Parses {@code US/TX} or {@code US/TX/Lubbock}. */
    public static AreaKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new RuleViolation("delivery-runs.invalid-area", "An area key is required, e.g. US/TX/Lubbock");
        }
        String[] parts = key.trim().split("/", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new RuleViolation("delivery-runs.invalid-area",
                    "Area keys are country/region or country/region/city, e.g. US/TX/Lubbock");
        }
        return new AreaKey(parts[0], parts[1], parts.length == 3 ? parts[2] : null);
    }

    public boolean isCity() {
        return city != null;
    }

    /** The region this key belongs to ({@code US/TX} for {@code US/TX/Lubbock}). */
    public AreaKey regionKey() {
        return isCity() ? new AreaKey(country, region, null) : this;
    }

    /** Whether this target covers a shopper's place (a region key covers every city in it). */
    public boolean covers(String placeCountry, String placeRegion, String placeCity) {
        if (placeCountry == null || placeRegion == null || !country.equalsIgnoreCase(placeCountry.trim())
                || !region.equalsIgnoreCase(placeRegion.trim())) {
            return false;
        }
        return city == null || (placeCity != null && city.equalsIgnoreCase(placeCity.trim()));
    }

    /** Same key, ignoring the case of the city. */
    public boolean sameAs(AreaKey other) {
        return other != null && country.equals(other.country) && region.equals(other.region)
                && (city == null ? other.city == null : other.city != null && city.equalsIgnoreCase(other.city));
    }

    /** Whether this key is {@code other}, inside it or contains it (window counts in coverage settings). */
    public boolean overlaps(AreaKey other) {
        if (!country.equals(other.country) || !region.equals(other.region)) {
            return false;
        }
        return city == null || other.city == null || city.equalsIgnoreCase(other.city);
    }

    /** {@code US/TX/Lubbock}. */
    public String key() {
        return city == null ? country + "/" + region : country + "/" + region + "/" + city;
    }

    /** "Lubbock, TX" or "All of TX", with the country appended outside the US. */
    public String label() {
        String base = city != null ? city + ", " + region : "All of " + region;
        return "US".equals(country) ? base : base + " · " + country;
    }

    @Override
    public String toString() {
        return key();
    }
}
