package com.afrotransact.fulfilment.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.RunBag;
import com.afrotransact.fulfilment.domain.RunDispatch;
import com.afrotransact.fulfilment.domain.RunDispatchStatus;

/** Delivery runs in dispatch, the routes and shipments planned for them, and the bags of pre-order shipments (S264). */
public interface RunDispatchRepository {

    /** Inserts the run, or refreshes what delivery-runs knows of it (schedule, pickup point, hub) when it exists. */
    void upsert(RunDispatch run);

    /** Writes the status, plan error, depot and planning time (optimistic on {@code version}). */
    void save(RunDispatch run);

    Optional<RunDispatch> find(String runId);

    /** Loads the run and locks its row until the transaction ends (planning and cancelling serialise on it). */
    Optional<RunDispatch> lock(String runId);

    /** Runs, the delivery day (latest first), optionally by status. */
    List<RunDispatch> list(RunDispatchStatus status, int limit);

    /** The route joined the run as its {@code seq}-th (1-based). */
    void linkRoute(String runId, String routeId, int seq);

    /** The run's route ids in sequence order. */
    List<String> routeIds(String runId);

    /** The run a route belongs to. */
    Optional<String> runOfRoute(String routeId);

    /** The shipment rides on the run (a shipment is on at most one run). */
    void linkShipment(String runId, String shipmentId);

    List<String> shipmentIds(String runId);

    /** The run a shipment rides on. */
    Optional<String> runOfShipment(String shipmentId);

    /** Records the bags of a pre-order shipment (ignored when it already has them). */
    void insertBags(List<RunBag> bags);

    /** Bags per shipment id (shipments without any are absent), the first bag first. */
    Map<String, List<RunBag>> bags(Collection<String> shipmentIds);
}
