package com.afrotransact.deliveryruns.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The coverage staff want for one state / province / region: its "Every city" row (absent, on or off) and the cities
 * listed in it, each on or off. Cities are de-duplicated ignoring case (the last entry wins).
 *
 * @param region      the region key ({@code US/TX})
 * @param wholeRegion null = no "Every city" row; true / false = the row, switched on / off
 * @param cities      cities of the region with their switch
 */
public record RegionCoverage(AreaKey region, Boolean wholeRegion, List<CityCoverage> cities) {

    /** Most cities one region edit may list. */
    public static final int MAX_CITIES = 2000;

    public RegionCoverage {
        if (region == null || region.isCity()) {
            throw new RuleViolation("delivery-runs.invalid-area", "Name a country and a region, e.g. US/TX");
        }
        Map<String, CityCoverage> byName = new LinkedHashMap<>();
        for (CityCoverage c : cities == null ? List.<CityCoverage>of() : cities) {
            if (c == null) {
                continue;
            }
            AreaKey k = new AreaKey(region.country(), region.region(), c.city());
            if (!k.isCity()) {
                throw new RuleViolation("delivery-runs.invalid-area", "Every city needs a name");
            }
            byName.put(k.city().toLowerCase(Locale.ROOT), new CityCoverage(k.city(), c.on()));
        }
        if (byName.size() > MAX_CITIES) {
            throw new RuleViolation("delivery-runs.too-many-cities",
                    "At most " + MAX_CITIES + " cities per region; cover the whole region instead");
        }
        cities = List.copyOf(new ArrayList<>(byName.values()));
    }

    /** The rows this coverage stands for. */
    public List<CoverageArea> areas() {
        List<CoverageArea> out = new ArrayList<>();
        if (wholeRegion != null) {
            out.add(new CoverageArea(region, wholeRegion));
        }
        cities.forEach(c -> out.add(new CoverageArea(new AreaKey(region.country(), region.region(), c.city()), c.on())));
        return out;
    }

    /**
     * A city of the region.
     *
     * @param city name as shown
     * @param on   pre-order shown there
     */
    public record CityCoverage(String city, boolean on) {
    }
}
