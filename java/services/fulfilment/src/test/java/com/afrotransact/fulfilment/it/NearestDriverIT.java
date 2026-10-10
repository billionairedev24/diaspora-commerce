package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.fulfilment.application.PlacedOrder;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S255: drivers' last known positions (location pings and synced events, PostGIS geography) and the nearest-driver
 * preference for seller pickups: KNN on fresh positions, availability filters, road-distance re-ranking from geo,
 * automatic assignment with an event, dispatch override with a logged reason, and the route builder as the fallback.
 */
class NearestDriverIT extends FulfilmentIntegrationTest {

    /** Ama's Market, downtown Houston. */
    private static final double STORE_LAT = 29.7589;
    private static final double STORE_LON = -95.3677;

    private String route(String driver, boolean inspected) {
        TestSupport.as(TestSupport.dispatcher());
        LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
        String routeId = ok(send("POST", "/v1/dispatch/routes", Map.of("zone", "Houston SW", "serviceDate",
                today.toString(), "depotNodeId", NODE)), 201).path("id").asString();
        ok(send("POST", "/v1/dispatch/routes/" + routeId + "/assign", Map.of("driverId", driver, "vehicleId",
                "VAN-" + driver)));
        if (inspected) {
            jdbc.sql("update delivery_route set inspection = 'PASSED' where id = :id").param("id", routeId).update();
        }
        return routeId;
    }

    private JsonNode ping(String driver, double lat, double lon, Instant at) {
        TestSupport.as(TestSupport.driver(driver));
        return ok(send("POST", "/v1/driver/location", Map.of("deviceId", "dev-" + driver, "lat", lat, "lon", lon,
                "accuracyM", 8, "capturedAt", at.toString())));
    }

    private PlacedOrder sellerPickupOrder(String orderId, String shipmentId, Double lat, Double lon) {
        PlacedOrder.PlacedShipment s = new PlacedOrder.PlacedShipment(shipmentId, "org-ama", "Ama's Market",
                "SELLER_FULFILLED", "AFROTRANSACT_DELIVERY", LocalDate.now().plusDays(1), false, false, null,
                List.of(new PlacedOrder.PlacedLine(UUID.randomUUID().toString(), "ATDEL00001", "Jollof kit", 1)),
                null, new PlacedOrder.SellerPickup("loc-ama", lat, lon));
        return order(orderId, "77042", s);
    }

    @Test
    void nearestAvailableDriverGetsTheSellerPickupAndDispatchCanOverride() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String near = id("drv-near");      // 2 km, eligible: closest by road
        String nearest = id("drv-knn");    // 1 km by air, eligible, but a long way round by road
        String far = id("drv-far");        // 5 km, eligible
        String stale = id("drv-stale");    // 0.5 km, position 20 minutes old
        String uninspected = id("drv-insp"); // 0.3 km, no passed inspection
        String routeNear = route(near, true);
        String routeKnn = route(nearest, true);
        String routeFar = route(far, true);
        route(stale, true);
        route(uninspected, false);
        ping(nearest, STORE_LAT + 0.009, STORE_LON, now.minusSeconds(30));
        ping(near, STORE_LAT + 0.018, STORE_LON, now.minusSeconds(60));
        ping(far, STORE_LAT + 0.045, STORE_LON, now.minusSeconds(60));
        ping(stale, STORE_LAT + 0.0045, STORE_LON, now.minusSeconds(1200));
        ping(uninspected, STORE_LAT + 0.0027, STORE_LON, now.minusSeconds(10));
        // An older fix never overwrites a newer one.
        JsonNode kept = ping(near, 0, 0, now.minusSeconds(3600));
        assertThat(kept.path("lat").asDouble()).isEqualTo(STORE_LAT + 0.018);

        // geo road distances from the pickup to the candidates in KNN order: nearest-by-air, near, far.
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/distance/batch")), 200, """
                {"results":[
                  {"index":0,"straightLineMiles":0.62,"roadMiles":6.0,"durationMinutes":19,"method":"ROAD_OSRM"},
                  {"index":1,"straightLineMiles":1.24,"roadMiles":2.0,"durationMinutes":7,"method":"ROAD_OSRM"},
                  {"index":2,"straightLineMiles":3.1,"roadMiles":4.0,"durationMinutes":11,"method":"ROAD_OSRM"}]}
                """);

        String orderId = UUID.randomUUID().toString();
        String shipmentId = UUID.randomUUID().toString();
        TestSupport.reset();
        intake.orderPlaced(UUID.randomUUID(), sellerPickupOrder(orderId, shipmentId, STORE_LAT, STORE_LON));

        assertThat(jdbc.sql("select route_id from delivery_shipment where shipment_id = :s").param("s", shipmentId)
                .query(String.class).single()).as("auto-assigned to the nearest by road").isEqualTo(routeNear);
        assertThat(outboxTypes(shipmentId)).contains("fulfilment.pickup.auto_assigned");
        JsonNode pickupStop = null;
        TestSupport.as(TestSupport.dispatcher());
        for (JsonNode stop : ok(send("GET", "/v1/dispatch/routes/" + routeNear, null)).path("stops")) {
            if ("PICKUP".equals(stop.path("kind").asString())) {
                pickupStop = stop;
            }
        }
        assertThat(pickupStop).as("pickup stop at the seller's store").isNotNull();

        JsonNode suggestions = ok(send("GET", "/v1/dispatch/pickups/" + shipmentId + "/driver-suggestions", null));
        List<String> ranked = new ArrayList<>();
        suggestions.path("drivers").forEach(d -> ranked.add(d.path("driverId").asString()));
        assertThat(ranked).containsExactly(near, far, nearest);
        assertThat(suggestions.path("drivers").get(0).path("roadMiles").asDouble()).isEqualTo(2.0);
        assertThat(suggestions.path("drivers").get(0).path("straightLineMiles").asDouble()).isBetween(1.1, 1.4);
        assertThat(suggestions.path("drivers").get(0).path("capacityLeft").asInt()).isEqualTo(39);

        assertThat(send("POST", "/v1/dispatch/pickups/" + shipmentId + "/assign", Map.of("routeId", routeFar,
                "reason", "no")).statusCode()).isEqualTo(400);
        JsonNode moved = ok(send("POST", "/v1/dispatch/pickups/" + shipmentId + "/assign", Map.of("routeId",
                routeFar, "reason", "Van of the nearest driver is full of frozen goods")));
        assertThat(moved.path("id").asString()).isEqualTo(routeFar);
        assertThat(jdbc.sql("select route_id from delivery_shipment where shipment_id = :s").param("s", shipmentId)
                .query(String.class).single()).isEqualTo(routeFar);
        JsonNode log = ok(send("GET", "/v1/dispatch/pickups/" + shipmentId + "/assignments", null));
        assertThat(log).hasSize(2);
        assertThat(log.get(0).path("mode").asString()).isEqualTo("AUTO_NEAREST");
        assertThat(log.get(0).path("roadMiles").asDouble()).isEqualTo(2.0);
        assertThat(log.get(1).path("mode").asString()).isEqualTo("DISPATCH_OVERRIDE");
        assertThat(log.get(1).path("reason").asString()).contains("frozen goods");
        assertThat(log.get(1).path("previousRouteId").asString()).isEqualTo(routeNear);
        assertThat(outboxTypes(shipmentId)).contains("fulfilment.pickup.dispatch_assigned");
        assertThat(routeKnn).isNotBlank();
    }

    @Test
    void withoutAPickupPositionTheZoneRouteBuilderTakesIt() {
        String shipmentId = UUID.randomUUID().toString();
        intake.orderPlaced(UUID.randomUUID(), sellerPickupOrder(UUID.randomUUID().toString(), shipmentId, null,
                null));
        assertThat(jdbc.sql("select status from delivery_shipment where shipment_id = :s").param("s", shipmentId)
                .query(String.class).single()).isEqualTo("AWAITING_ROUTE");
        TestSupport.as(TestSupport.dispatcher());
        assertThat(ok(send("GET", "/v1/dispatch/pickups/" + shipmentId + "/driver-suggestions", null))
                .path("drivers")).isEmpty();
    }

    @Test
    void syncedEventsMoveTheDriversPositionAndPingsNeedDriverAccess() {
        String driver = id("drv-sync");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ping(driver, 29.70, -95.40, now.minusSeconds(300));
        TestSupport.as(TestSupport.driver(driver));
        sync("dev-" + driver, List.of(event(1, "session.signed_in", null, null, now.minusSeconds(5))), "tok");
        assertThat(jdbc.sql("select source from driver_position where driver_id = :d").param("d", driver)
                .query(String.class).single()).isEqualTo("SYNC");
        assertThat(jdbc.sql("select ST_Y(position::geometry) from driver_position where driver_id = :d")
                .param("d", driver).query(Double.class).single()).isEqualTo(29.7376);

        TestSupport.as(TestSupport.dispatcher());
        assertThat(send("POST", "/v1/driver/location", Map.of("lat", 29.7, "lon", -95.4)).statusCode())
                .isEqualTo(403);
        TestSupport.as(TestSupport.driver(driver));
        assertThat(send("POST", "/v1/driver/location", Map.of("lat", 129.7, "lon", -95.4)).statusCode())
                .isEqualTo(400);
    }
}
