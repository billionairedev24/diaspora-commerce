package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.Map;

/**
 * Live server-sent event feed: dispatch consoles see every update; a driver's own stream sees updates addressed to
 * them (route changes, sign-out). Published after the transaction commits.
 */
public interface DispatchFeed {

    void publish(Update update);

    /**
     * @param name     SSE event name (driver.event, driver.sos, vehicle.defect, route.updated, session.revoked …)
     * @param driverId driver the update concerns (null for console-only updates)
     * @param routeId  route the update concerns
     * @param priority HIGH for SOS and defects
     * @param data     JSON body
     * @param at       server time
     */
    record Update(String name, String driverId, String routeId, String priority, Map<String, Object> data,
                  Instant at) {
    }
}
