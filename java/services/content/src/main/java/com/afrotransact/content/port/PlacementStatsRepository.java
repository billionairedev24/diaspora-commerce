package com.afrotransact.content.port;

import java.time.LocalDate;

/** Impressions and clicks of a placement per UTC day (paid home modules report them through the gateway). */
public interface PlacementStatsRepository {

    /** Adds to the day's counters; false when no such placement exists. */
    boolean add(String placementId, LocalDate day, long impressions, long clicks);
}
