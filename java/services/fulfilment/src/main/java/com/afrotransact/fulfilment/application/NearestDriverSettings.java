package com.afrotransact.fulfilment.application;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Nearest-driver preference for seller pickups (S255, {@code at.fulfilment.nearest-driver.*}).
 *
 * @param enabled             auto-assign new SELLER-origin pickups (suggestions work either way)
 * @param staleAfter          driver positions older than this are ignored (default 10 minutes)
 * @param maxPackagesPerRoute packages still to deliver a route can carry before it is full
 * @param candidates          nearest drivers (KNN) re-ranked by road distance
 * @param zone                time zone of the routes' service day
 */
public record NearestDriverSettings(boolean enabled, Duration staleAfter, int maxPackagesPerRoute, int candidates,
                                    ZoneId zone) {

    /** Suggestions shown to dispatch. */
    public static final int SUGGESTIONS = 5;

    public NearestDriverSettings {
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(zone, "zone");
        candidates = Math.max(SUGGESTIONS, candidates);
    }
}
