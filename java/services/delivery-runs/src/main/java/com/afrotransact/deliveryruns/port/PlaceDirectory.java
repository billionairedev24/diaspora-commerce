package com.afrotransact.deliveryruns.port;

import java.util.List;

/** Countries, regions and cities for the area pickers and the coverage drill-down. */
public interface PlaceDirectory {

    /**
     * Places whose name contains {@code q} (at least 2 characters), best first.
     *
     * @param limit at most this many
     */
    List<Place> search(String q, int limit);

    /** Browsing: every country (no country), a country's regions, or a region's cities. */
    List<Place> browse(String country, String region);

    /** Country code of a region code ({@code TX → US}, {@code ON → CA}); empty string when unknown. */
    String countryOfRegion(String region);

    /** "United States"; the code when unknown. */
    String countryName(String country);

    /** "Texas"; the code when unknown. */
    String regionName(String country, String region);

    /** What a country calls its regions: State, Province, Nation, County, Parish, Region. */
    String regionLabel(String country);

    /**
     * A place.
     *
     * @param key   {@code US}, {@code US/TX} or {@code US/TX/Lubbock}
     * @param code  {@code US}, {@code TX} or {@code Lubbock}
     * @param name  "United States", "Texas", "Lubbock"
     * @param label "Texas" / "All of Texas" (search), "Lubbock, TX"
     * @param sub   "State · United States"
     * @param type  COUNTRY, REGION or CITY
     */
    record Place(String key, String code, String name, String label, String sub, String type) {
    }
}
