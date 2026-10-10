package com.afrotransact.deliveryruns.domain;

/** How a location's area is defined in geo-service. */
public enum AreaKind {
    /** A polygon zone stored in geo-service (by id); coverage via {@code GET /v1/zones/covering}. */
    POLYGON,
    /** A circle: centre + radius in miles; coverage via geo-service distances. */
    RADIUS
}
