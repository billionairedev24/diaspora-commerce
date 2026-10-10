package com.afrotransact.fulfilment.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Groups shipments by zone (sorted), orders drop-offs by ZIP then order number so neighbours sit together, and splits
 * a zone into several routes when it has more than {@code maxDropoffsPerRoute} drop-offs (one drop-off per order).
 * Shipments of the same order always stay on the same route.
 */
public final class ZoneRoutePlanner implements RoutePlanner {

    @Override
    public List<PlannedRoute> plan(List<RoutableShipment> shipments, int maxDropoffsPerRoute) {
        if (maxDropoffsPerRoute < 1) {
            throw new FulfilmentRuleViolation("fulfilment.max-stops-invalid", "maxStops must be at least 1");
        }
        Map<String, List<RoutableShipment>> byZone = new TreeMap<>();
        for (RoutableShipment s : shipments) {
            String zone = s.zone() == null || s.zone().isBlank() ? "UNZONED" : s.zone();
            byZone.computeIfAbsent(zone, z -> new ArrayList<>()).add(s);
        }
        List<PlannedRoute> routes = new ArrayList<>();
        for (Map.Entry<String, List<RoutableShipment>> zone : byZone.entrySet()) {
            Map<String, List<RoutableShipment>> byOrder = new LinkedHashMap<>();
            zone.getValue().stream()
                    .sorted(Comparator.comparing((RoutableShipment s) -> s.postalCode() == null ? "" : s.postalCode())
                            .thenComparing(RoutableShipment::orderNumber)
                            .thenComparing(RoutableShipment::shipmentId))
                    .forEach(s -> byOrder.computeIfAbsent(s.orderNumber(), o -> new ArrayList<>()).add(s));
            List<RoutableShipment> current = new ArrayList<>();
            Set<String> orders = new LinkedHashSet<>();
            for (Map.Entry<String, List<RoutableShipment>> order : byOrder.entrySet()) {
                if (orders.size() == maxDropoffsPerRoute) {
                    routes.add(new PlannedRoute(zone.getKey(), current));
                    current = new ArrayList<>();
                    orders.clear();
                }
                orders.add(order.getKey());
                current.addAll(order.getValue());
            }
            if (!current.isEmpty()) {
                routes.add(new PlannedRoute(zone.getKey(), current));
            }
        }
        return routes;
    }
}
