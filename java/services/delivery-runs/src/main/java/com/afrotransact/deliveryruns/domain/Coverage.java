package com.afrotransact.deliveryruns.domain;

import java.util.List;

/**
 * Platform settings plus coverage areas.
 *
 * @param settings master switch, fallback, placements and payment
 * @param areas    coverage switches
 */
public record Coverage(PreorderSettings settings, List<CoverageArea> areas) {

    public Coverage {
        settings = settings == null ? PreorderSettings.DEFAULT : settings;
        areas = areas == null ? List.of() : List.copyOf(areas);
    }

    /** {@code at-preorder.js#areaOn}: a matching city row wins; otherwise the region row; otherwise off. */
    public boolean areaOn(String country, String region, String city) {
        return areaOn(areas, country, region, city);
    }

    public static boolean areaOn(List<CoverageArea> areas, String country, String region, String city) {
        if (country == null || region == null) {
            return false;
        }
        if (city != null && !city.isBlank()) {
            for (CoverageArea a : areas) {
                AreaKey k = a.key();
                if (k.isCity() && k.country().equalsIgnoreCase(country) && k.region().equalsIgnoreCase(region)
                        && k.city().equalsIgnoreCase(city.trim())) {
                    return a.on();
                }
            }
        }
        for (CoverageArea a : areas) {
            AreaKey k = a.key();
            if (!k.isCity() && k.country().equalsIgnoreCase(country) && k.region().equalsIgnoreCase(region)) {
                return a.on();
            }
        }
        return false;
    }

    /**
     * Whether a window target is switched on: a city target by {@link #areaOn}, a region target only by its own
     * region row ({@code openList} in the design).
     */
    public boolean targetOn(AreaKey target) {
        if (target.isCity()) {
            return areaOn(target.country(), target.region(), target.city());
        }
        return areas.stream().anyMatch(a -> a.key().sameAs(target) && a.on());
    }

    /** Whether every target of the window is off ("Area off" in the staff list). */
    public boolean allTargetsOff(List<AreaKey> targets) {
        return !targets.isEmpty() && targets.stream().noneMatch(this::targetOn);
    }
}
