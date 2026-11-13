package com.afrotransact.fulfilment.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.ReturnPickup;
import com.afrotransact.fulfilment.domain.ReturnPickupStatus;

/**
 * Return pickups: tax-returns schedules one per DRIVER_PICKUP RMA ({@code delivery:returns} service scope, or
 * dispatch), dispatch puts them on routes as RETURN_PICKUP stops, drivers collect or fail them through the sync.
 */
public interface ReturnPickupService {

    /** Idempotent per RMA: a retry returns the existing pickup (any status). */
    Result schedule(ScheduleReturnPickupCommand command);

    ReturnPickup get(String id);

    /**
     * The driver of the route carrying the pickup's stop (empty while the pickup is not on a route or the route has
     * no driver yet). tax-returns checks it before it accepts a driver's handover code.
     */
    Optional<String> assignedDriver(String id);

    List<ReturnPickup> list(ReturnPickupStatus status, String depotNodeId, int limit);

    /** tax-returns (RMA cancelled) or dispatch; takes the stop off an unstarted route. Idempotent. */
    ReturnPickup cancel(String id, String reason);

    /** Dispatch: adds REQUESTED pickups as RETURN_PICKUP stops to a planned, assigned or running route. */
    DeliveryRoute addToRoute(String routeId, List<String> pickupIds);

    /** A pickup and whether this call created it. */
    record Result(ReturnPickup pickup, boolean created) {
    }
}
