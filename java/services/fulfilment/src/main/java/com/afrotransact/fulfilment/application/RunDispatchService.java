package com.afrotransact.fulfilment.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.RunDispatchStatus;
import com.afrotransact.fulfilment.port.DeliveryRunsClient.RunPlan;

/**
 * Plans and follows delivery runs (S264). When delivery-runs confirms a run (a closed pre-order window's paid orders)
 * fulfilment plans one sweep: the shipments of the run's orders become one route per capacity chunk, with a pickup stop
 * per seller before the drops, doorstep drops ordered by road distance from the hub or one drop per order at the
 * community pickup point (each needing the customer's handover code). Drivers then work the routes through the usual
 * driver flow, whose shipment events move the run along in delivery-runs.
 */
public interface RunDispatchService {

    String CONSUMER = "fulfilment.delivery-runs";

    /**
     * {@code delivery-runs.run.confirmed}: remembers the run and plans its routes. The event carries what is needed to
     * remember the run even when delivery-runs cannot be reached to plan it ({@code fallback}); staff then plan it
     * from the console. Repeated events change nothing.
     *
     * @param fallback the run as the event describes it (its orders are the event's)
     */
    void onRunConfirmed(UUID eventId, RunPlan fallback);

    /**
     * {@code delivery-runs.run.cancelled}: routes that have not started are cancelled; one that has is left to the
     * driver (the orders are cancelled by orders-service, which alerts the route).
     */
    void onRunCancelled(UUID eventId, String runId, String reason);

    /**
     * Plans the routes of a run again (dispatch: {@code delivery:dispatch}): shipments that are not on a live route
     * (their route was cancelled, or they arrived late) get new routes. Planned routes are never touched.
     */
    RunDispatchView plan(String runId);

    /** Runs, the latest delivery day first (dispatch: {@code fulfilment:operate} or {@code delivery:dispatch}). */
    List<RunDispatchView> list(RunDispatchStatus status, int limit);

    RunDispatchView get(String runId);
}
