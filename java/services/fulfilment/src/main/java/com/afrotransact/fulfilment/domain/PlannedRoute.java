package com.afrotransact.fulfilment.domain;

import java.util.List;

/** One route proposed by the planner: a zone and the shipments it delivers, in visiting order. */
public record PlannedRoute(String zone, List<RoutableShipment> shipments) {

    public PlannedRoute {
        shipments = List.copyOf(shipments);
    }
}
