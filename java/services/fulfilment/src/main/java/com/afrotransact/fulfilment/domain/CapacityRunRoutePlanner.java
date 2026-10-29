package com.afrotransact.fulfilment.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orders drops by road distance from the hub and cuts them into the fewest routes that fit, then evens the routes
 * out: the smallest capacity that still needs no more routes than the fewest, so a run of 50 bags with room for 40
 * makes two routes of 25 rather than 40 and 10. Routes are contiguous in the visiting order, so each covers one
 * stretch of the way out.
 */
public final class CapacityRunRoutePlanner implements RunRoutePlanner {

    @Override
    public List<List<RunDrop>> plan(List<RunDrop> drops, int maxPackagesPerRoute) {
        if (maxPackagesPerRoute < 1) {
            throw new FulfilmentRuleViolation("fulfilment.route-capacity-invalid",
                    "A route must carry at least one package");
        }
        if (drops.isEmpty()) {
            return List.of();
        }
        List<RunDrop> ordered = drops.stream()
                .sorted(Comparator.comparing((RunDrop d) -> d.distanceMeters() == null)
                        .thenComparing(d -> d.distanceMeters() == null ? 0.0 : d.distanceMeters())
                        .thenComparing(RunDrop::orderNumber))
                .toList();
        int total = ordered.stream().mapToInt(RunDrop::packages).sum();
        int fewest = cut(ordered, maxPackagesPerRoute).size();
        int low = Math.min(maxPackagesPerRoute, (total + fewest - 1) / fewest);
        int high = maxPackagesPerRoute;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cut(ordered, mid).size() <= fewest) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return cut(ordered, low);
    }

    /** Fills routes in order up to {@code capacity} packages; an order bigger than that gets a route of its own. */
    private static List<List<RunDrop>> cut(List<RunDrop> ordered, int capacity) {
        List<List<RunDrop>> routes = new ArrayList<>();
        List<RunDrop> current = new ArrayList<>();
        int load = 0;
        for (RunDrop d : ordered) {
            if (!current.isEmpty() && load + d.packages() > capacity) {
                routes.add(current);
                current = new ArrayList<>();
                load = 0;
            }
            current.add(d);
            load += d.packages();
        }
        routes.add(current);
        return routes;
    }
}
