package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.RunDispatch;

/**
 * A run as dispatch sees it (S264): the run, its routes (with stops and packages) and how far it has got.
 *
 * @param run      the run
 * @param routes   its routes, in the order they were planned
 * @param progress counts over the run's shipments and routes
 */
public record RunDispatchView(RunDispatch run, List<DeliveryRoute> routes, Progress progress) {

    public RunDispatchView {
        routes = List.copyOf(routes);
    }

    /**
     * What has happened on the run's routes.
     *
     * @param orders          orders on the run
     * @param bags            packages (one seller's bag of an order) on live routes
     * @param pickupStops     seller pickup stops
     * @param pickupsDone     pickup stops finished
     * @param bagsCollected   bags collected from sellers (or loaded at the depot)
     * @param dropStops       drop-off stops
     * @param dropsDone       drop-off stops delivered
     * @param dropsFailed     drop-off stops that failed or were skipped
     * @param bagsDelivered   bags delivered
     * @param unrouted        run shipments waiting for a route (a route was cancelled, or planning is not done)
     */
    public record Progress(int orders, int bags, int pickupStops, int pickupsDone, int bagsCollected, int dropStops,
                           int dropsDone, int dropsFailed, int bagsDelivered, int unrouted) {
    }
}
