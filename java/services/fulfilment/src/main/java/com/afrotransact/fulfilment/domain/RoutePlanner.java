package com.afrotransact.fulfilment.domain;

import java.util.List;

/** Simple route builder: groups waiting shipments by delivery zone and caps drop-offs per route. */
public interface RoutePlanner {

    List<PlannedRoute> plan(List<RoutableShipment> shipments, int maxDropoffsPerRoute);
}
