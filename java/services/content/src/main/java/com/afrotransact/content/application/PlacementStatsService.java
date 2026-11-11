package com.afrotransact.content.application;

/** Counts impressions and clicks on placements. Nothing identifies the visitor. */
public interface PlacementStatsService {

    /** @param kind {@code impression} or {@code click}; anything else is rejected */
    void record(String placementId, String kind);
}
