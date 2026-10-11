package com.afrotransact.content.domain.market;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * An immutable, published version of Markets & locations. Every eligibility answer comes from one snapshot.
 *
 * @param version     publication number
 * @param publishedAt when
 * @param publishedBy who
 * @param countries   all countries with their regions
 */
public record MarketSnapshot(int version, Instant publishedAt, String publishedBy, List<Country> countries) {

    public MarketSnapshot {
        countries = List.copyOf(countries);
    }

    public Optional<Country> country(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return countries.stream().filter(x -> x.code().equals(c)).findFirst();
    }

    /**
     * Whether the location can be served. The state is taken from {@code region} or, failing that, from the
     * postal code. When both are given they must agree.
     */
    public Eligibility eligibility(String countryCode, String region, String postalCode) {
        return eligibility(countryCode, region, postalCode, null);
    }

    /**
     * Like {@link #eligibility(String, String, String)}, and when {@code city} is given the live state's cities
     * decide too: a city listed PLANNED or OFF is not served, and when the state does not cover every city only the
     * cities listed LIVE are. Without a city the state decides alone.
     */
    public Eligibility eligibility(String countryCode, String region, String postalCode, String city) {
        Optional<Country> found = country(countryCode);
        if (found.isEmpty()) {
            return new Eligibility(upper(countryCode), null, null, false, false, Capabilities.allOff(),
                    "unsupported-country", "AfroTransact does not operate in this country.", null, false, version);
        }
        Country c = found.get();
        if (!c.live()) {
            String msg = switch (c.status()) {
                case PAUSED -> "AfroTransact is paused in " + c.name() + ".";
                case COMING_SOON -> "AfroTransact is coming soon to " + c.name() + ".";
                default -> "We're not open in " + c.name() + " yet.";
            };
            return new Eligibility(c.code(), c.status(), upper(region), false, false, Capabilities.allOff(),
                    "country-not-live", msg, c.launchNote(), c.status() != MarketStatus.PAUSED, version);
        }
        if (c.regions().isEmpty() || isBlank(region) && isBlank(postalCode)) {
            return new Eligibility(c.code(), c.status(), null, true, true, Capabilities.allOn(), "ok",
                    "AfroTransact is live in " + c.name() + ".", c.launchNote(), false, version);
        }
        Optional<Region> byCode = c.region(region);
        Optional<Region> byZip = c.regionForPostalCode(postalCode);
        if (!isBlank(region) && byCode.isEmpty()) {
            return blocked(c, upper(region), "region-unknown", "We don't recognise that state or province.");
        }
        if (!isBlank(postalCode) && byZip.isEmpty() && byCode.isEmpty()) {
            return blocked(c, null, "postal-code-unknown", "We don't recognise that ZIP or postal code.");
        }
        if (byCode.isPresent() && byZip.isPresent() && !byCode.get().code().equals(byZip.get().code())) {
            return blocked(c, byCode.get().code(), "postal-code-mismatch",
                    "That ZIP code is in " + byZip.get().name() + ", not " + byCode.get().name() + ".");
        }
        Region r = byCode.orElseGet(byZip::orElseThrow);
        if (!r.capabilities().marketplace()) {
            return new Eligibility(c.code(), c.status(), r.code(), false, false, r.capabilities(), "region-disabled",
                    "AfroTransact doesn't deliver to " + r.name() + " yet.", c.launchNote(), true, version);
        }
        if (!isBlank(city)) {
            Optional<City> listed = r.city(city);
            if (listed.isPresent() && listed.get().status() != LocationStatus.LIVE) {
                return cityBlocked(c, r, "city-not-live", "AfroTransact doesn't deliver to " + listed.get().name()
                        + " yet.");
            }
            if (listed.isEmpty() && !r.everyCity()) {
                return cityBlocked(c, r, "city-not-covered", "AfroTransact doesn't deliver to " + city.trim()
                        + " yet.");
            }
        }
        return new Eligibility(c.code(), c.status(), r.code(), true, true, r.capabilities(), "ok",
                "AfroTransact delivers to " + r.name() + ".", c.launchNote(), false, version);
    }

    private Eligibility cityBlocked(Country c, Region r, String reason, String message) {
        return new Eligibility(c.code(), c.status(), r.code(), true, false, r.capabilities(), reason, message,
                c.launchNote(), true, version);
    }

    private Eligibility blocked(Country c, String region, String reason, String message) {
        return new Eligibility(c.code(), c.status(), region, false, false, Capabilities.allOff(), reason, message,
                c.launchNote(), false, version);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
