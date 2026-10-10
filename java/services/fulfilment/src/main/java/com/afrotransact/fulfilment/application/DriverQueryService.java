package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.domain.DeliveryRoute;

/** What the driver app downloads for offline work: today's routes with stops, packages and manifest. */
public interface DriverQueryService {

    List<DeliveryRoute> myRoutes(DriverCredential credential);

    DeliveryRoute myRoute(DriverCredential credential, String routeId);

    /** The calling driver's id (for the driver's own SSE stream); throws without access. */
    String driverId(DriverCredential credential);
}
