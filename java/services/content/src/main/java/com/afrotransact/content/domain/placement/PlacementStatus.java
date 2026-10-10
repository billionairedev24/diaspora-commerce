package com.afrotransact.content.domain.placement;

/** Review workflow: DRAFT → IN_REVIEW → SCHEDULED → LIVE → ENDED (rejection returns to DRAFT). */
public enum PlacementStatus {
    DRAFT,
    IN_REVIEW,
    SCHEDULED,
    LIVE,
    ENDED
}
