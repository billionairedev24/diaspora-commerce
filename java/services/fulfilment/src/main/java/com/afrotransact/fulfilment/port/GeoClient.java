package com.afrotransact.fulfilment.port;

import java.util.Optional;

import com.afrotransact.fulfilment.domain.GeoPoint;

/** geo-service: ZIP centroid and delivery zones covering a point (public endpoints). Empty on any failure. */
public interface GeoClient {

    Optional<GeoPoint> locateZip(String zip);

    /** Name of the active DELIVERY zone containing the point. */
    Optional<String> deliveryZoneAt(GeoPoint point);
}
