package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.RunPickupPoint;

/**
 * delivery-runs-service: the confirmed runs fulfilment plans (S264). Failures throw {@link UpstreamFailure}
 * (the service token needs {@code delivery-runs:read}).
 */
public interface DeliveryRunsClient {

    /** {@code GET /v1/service/runs/{runId}/dispatch}: the run, where it leaves from and its confirmed orders. */
    RunPlan dispatch(String runId);

    /**
     * What a run needs planned.
     *
     * @param status         the run's status in delivery-runs
     * @param town           location name
     * @param state          state code
     * @param timeZone       IANA zone of the location
     * @param windowStart    delivery window start
     * @param windowEnd      delivery window end
     * @param sellerPickupAt when the driver collects from sellers (may be null)
     * @param doorstep       true for doorstep drops, false for a pickup point
     * @param pickupPoint    the pickup point of a pickup-point run
     * @param origin         the hub
     * @param orders         the run's confirmed orders (delivered ones included: they are routed already)
     */
    record RunPlan(String runId, String status, String town, String state, String timeZone, Instant windowStart,
                   Instant windowEnd, Instant sellerPickupAt, boolean doorstep, RunPickupPoint pickupPoint,
                   GeoPoint origin, List<String> orders) {

        public RunPlan {
            orders = List.copyOf(orders);
        }
    }
}
