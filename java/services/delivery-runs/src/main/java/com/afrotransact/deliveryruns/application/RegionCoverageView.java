package com.afrotransact.deliveryruns.application;

import java.util.List;

/**
 * One region's coverage as the in-place editor shows it: the "Every city" row and every listed city with its switch,
 * the pre-orders that sell there, and the version to send back (If-Match) when saving.
 *
 * @param country     ISO country code
 * @param region      region code
 * @param countryName "United States"
 * @param regionName  "Texas"
 * @param regionLabel what the country calls regions (State, Province …)
 * @param wholeRegion the "Every city in …" row, or null when the region is covered city by city
 * @param cities      cities listed, by name
 * @param version     optimistic lock of the region's rows (ETag)
 */
public record RegionCoverageView(String country, String region, String countryName, String regionName,
                                 String regionLabel, RegionRow wholeRegion, List<RegionRow> cities, long version) {

    public RegionCoverageView {
        cities = List.copyOf(cities);
    }

    /**
     * A row.
     *
     * @param key      area key
     * @param name     city name, or "Every city in Texas"
     * @param on       switched on
     * @param version  the row's version
     * @param windows  published, not yet delivered pre-orders that sell here (they block removing the row)
     */
    public record RegionRow(String key, String name, boolean on, long version, List<String> windows) {

        public RegionRow {
            windows = List.copyOf(windows);
        }
    }
}
