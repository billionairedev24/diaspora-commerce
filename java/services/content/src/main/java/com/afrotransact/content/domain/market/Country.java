package com.afrotransact.content.domain.market;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * A country in Markets & locations.
 *
 * @param code       ISO 3166-1 alpha-2
 * @param name       English name
 * @param status     LIVE, PAUSED, COMING_SOON or PLANNED
 * @param launchNote message shown to shoppers and sellers (e.g. Canada's early access terms)
 * @param currency   ISO 4217 currency
 * @param sortOrder  picker order (launch markets first)
 * @param regions    states / provinces (empty for countries without regional switches)
 */
public record Country(String code, String name, MarketStatus status, String launchNote, String currency,
                      int sortOrder, List<Region> regions) {

    public Country {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    public Optional<Region> region(String regionCode) {
        if (regionCode == null) {
            return Optional.empty();
        }
        String c = regionCode.trim().toUpperCase(Locale.ROOT);
        return regions.stream().filter(r -> r.code().equals(c)).findFirst();
    }

    public Optional<Region> regionForPostalCode(String postalCode) {
        return regions.stream().filter(r -> r.coversPostalCode(postalCode)).findFirst();
    }

    public Region requireRegion(String regionCode) {
        return region(regionCode).orElseThrow(() -> new ContentRuleViolation("content.unknown-region",
                "No region " + regionCode + " in " + code, Map.of("region", "Unknown")));
    }

    public Country withStatus(MarketStatus next, String note) {
        return new Country(code, name, next, note, currency, sortOrder, regions);
    }

    public Country withRegion(Region updated) {
        return new Country(code, name, status, launchNote, currency, sortOrder,
                regions.stream().map(r -> r.code().equals(updated.code()) ? updated : r).toList());
    }

    /** "Activate all": every capability on in every region. */
    public Country activateAll() {
        return new Country(code, name, status, launchNote, currency, sortOrder,
                regions.stream().map(r -> r.withCapabilities(Capabilities.allOn())).toList());
    }

    /** "Deactivate all": every capability off in every region. */
    public Country deactivateAll() {
        return new Country(code, name, status, launchNote, currency, sortOrder,
                regions.stream().map(r -> r.withCapabilities(Capabilities.allOff())).toList());
    }

    public boolean live() {
        return status == MarketStatus.LIVE;
    }
}
