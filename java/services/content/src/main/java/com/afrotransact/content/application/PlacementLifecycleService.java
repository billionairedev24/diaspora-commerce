package com.afrotransact.content.application;

/** Moves approved placements through their schedule: SCHEDULED → LIVE when it starts, → ENDED when it ends. */
public interface PlacementLifecycleService {

    /** Runs one pass; returns how many placements changed status. */
    int advance();
}
