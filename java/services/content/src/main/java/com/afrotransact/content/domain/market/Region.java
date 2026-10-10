package com.afrotransact.content.domain.market;

import java.util.List;
import java.util.Locale;

/**
 * A state, district, province or nation with its capability switches.
 *
 * @param country      ISO country code
 * @param code         subdivision code (TX, ON, ENG…)
 * @param name         display name
 * @param kind         subdivision kind
 * @param capabilities switches
 * @param zipPrefixes  postal code prefixes that belong to it (3-digit US ZIP prefixes, Canadian FSA letters)
 */
public record Region(String country, String code, String name, RegionKind kind, Capabilities capabilities,
                     List<String> zipPrefixes) {

    public Region {
        zipPrefixes = zipPrefixes == null ? List.of() : List.copyOf(zipPrefixes);
    }

    public boolean coversPostalCode(String postalCode) {
        if (postalCode == null || postalCode.isBlank()) {
            return false;
        }
        String normalised = postalCode.trim().toUpperCase(Locale.ROOT);
        return zipPrefixes.stream().anyMatch(normalised::startsWith);
    }

    public Region withCapabilities(Capabilities next) {
        return new Region(country, code, name, kind, next, zipPrefixes);
    }
}
