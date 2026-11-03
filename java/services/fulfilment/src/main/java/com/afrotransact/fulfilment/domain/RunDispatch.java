package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A delivery run known to dispatch (S264): what delivery-runs handed over when it confirmed the run, and whether its
 * routes are planned.
 *
 * @param runId          delivery-runs run id
 * @param town           location name ("Bastrop")
 * @param state          state code
 * @param timeZone       IANA zone of the location
 * @param serviceDate    the delivery day (in the location's zone)
 * @param windowStart    delivery window start
 * @param windowEnd      delivery window end
 * @param sellerPickupAt when the driver collects from the sellers (null when not scheduled)
 * @param doorstep       true for doorstep drops, false for a community pickup point
 * @param pickupPoint    the pickup point of a pickup-point run
 * @param origin         the hub the run leaves from; drops are ordered by road distance from it
 * @param depotNodeId    the fulfilment centre the routes load out of
 * @param status         dispatch status
 * @param planError      why planning could not finish (null when it did)
 * @param plannedAt      when the routes were last planned
 * @param version        optimistic lock
 */
public record RunDispatch(String runId, String town, String state, String timeZone, LocalDate serviceDate,
                          Instant windowStart, Instant windowEnd, Instant sellerPickupAt, boolean doorstep,
                          RunPickupPoint pickupPoint, GeoPoint origin, String depotNodeId, RunDispatchStatus status,
                          String planError, Instant plannedAt, int version) {

    public RunDispatch planned(String depot, Instant at) {
        return new RunDispatch(runId, town, state, timeZone, serviceDate, windowStart, windowEnd, sellerPickupAt,
                doorstep, pickupPoint, origin, depot, RunDispatchStatus.PLANNED, null, at, version);
    }

    public RunDispatch failed(String error) {
        return new RunDispatch(runId, town, state, timeZone, serviceDate, windowStart, windowEnd, sellerPickupAt,
                doorstep, pickupPoint, origin, depotNodeId, RunDispatchStatus.PENDING, error, plannedAt, version);
    }

    public RunDispatch cancelled() {
        return new RunDispatch(runId, town, state, timeZone, serviceDate, windowStart, windowEnd, sellerPickupAt,
                doorstep, pickupPoint, origin, depotNodeId, RunDispatchStatus.CANCELLED, planError, plannedAt,
                version);
    }
}
