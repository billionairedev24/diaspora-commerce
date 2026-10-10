package com.afrotransact.deliveryruns.application;

import java.util.List;

/**
 * Coverage drill-down: countries (COUNTRY), a country's regions (REGION), then a region's areas (AREA: the region row
 * "Every city" and its cities).
 *
 * @param level       COUNTRY, REGION or AREA (what the rows are)
 * @param country     the country drilled into (null at COUNTRY level)
 * @param region      the region drilled into (AREA level only)
 * @param countryName e.g. "United States"
 * @param regionName  e.g. "Texas"
 * @param regionLabel what the country calls regions (State, Province …)
 * @param rows        rows of this page
 * @param total       rows matching
 * @param page        zero-based page
 * @param size        page size (12 in the console)
 * @param onCount     areas switched on (all areas)
 * @param offCount    areas switched off (all areas)
 * @param saved       every area switched on (the wizard's "reuse a saved area"), by label
 */
public record AreaPage(String level, String country, String region, String countryName, String regionName,
                       String regionLabel, List<Row> rows, int total, int page, int size, int onCount, int offCount,
                       List<Saved> saved) {

    /**
     * One row.
     *
     * @param key      {@code US}, {@code US/TX} or the area key {@code US/TX/Lubbock}
     * @param name     country / region name, city name, or "Every city in Texas" for a region area
     * @param coverage "2 states · 5 cities", "Every city + 3 cities", "City", "Whole state"
     * @param windows  published windows targeting it (or inside it)
     * @param on       areas switched on inside (an AREA row is 1 or 0)
     * @param total    areas inside (an AREA row is 1)
     * @param leaf     a single area, which can be removed
     */
    public record Row(String key, String name, String coverage, int windows, int on, int total, boolean leaf) {
    }

    /** A saved area for the wizard's picker. */
    public record Saved(String key, String label) {
    }
}
