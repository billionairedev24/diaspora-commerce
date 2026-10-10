package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.fulfilment.application.OrdersRelayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S088 reassignment of a failed stop; S087 proof of delivery while orders-service is unreachable. */
class ReassignAndOfflineIT extends FulfilmentIntegrationTest {

    private final OrdersRelayService relay;

    @Autowired
    ReassignAndOfflineIT(OrdersRelayService relay) {
        this.relay = relay;
    }

    @Test
    void failedStopIsReassignedAndDeliveredWhileOrdersIsDown() {
        String orderId = UUID.randomUUID().toString();
        String shipment = UUID.randomUUID().toString();
        UPSTREAMS.reservation(orderId, NODE, "ATVSOAP0003");
        intake.orderPlaced(UUID.randomUUID(), order(orderId, "77042",
                shipment(shipment, "FBA_AFROTRANSACT", "AFROTRANSACT_DELIVERY", true, false, "ATVSOAP0003")));
        String d1 = id("driver");
        String d2 = id("driver");

        TestSupport.as(TestSupport.dispatcher());
        JsonNode r1 = ok(send("POST", "/v1/dispatch/routes", Map.of("name", "Morning", "zone", "Houston SW",
                "serviceDate", "2026-10-13", "window", "9:00–1:00", "depotNodeId", NODE,
                "shipmentIds", List.of(shipment))), 201);
        JsonNode r2 = ok(send("POST", "/v1/dispatch/routes", Map.of("name", "Afternoon", "zone", "Houston SW",
                "serviceDate", "2026-10-13", "depotNodeId", NODE)), 201);
        String route1 = r1.path("id").asString();
        String route2 = r2.path("id").asString();
        problem(send("POST", "/v1/dispatch/routes/" + route2 + "/shipments",
                Map.of("shipmentIds", List.of(shipment))), 409, "fulfilment.shipment-not-routable");
        ok(send("POST", "/v1/dispatch/routes/" + route1 + "/assign", Map.of("driverId", d1, "vehicleId", "VAN-1")));
        ok(send("POST", "/v1/dispatch/routes/" + route2 + "/assign", Map.of("driverId", d2, "vehicleId", "VAN-2")));
        String label = r1.path("packages").get(0).path("label").asString();
        String stop1 = r1.path("stops").get(0).path("id").asString();

        // ---- driver 1 cannot reach the customer and returns the package
        TestSupport.as(TestSupport.driver(d1));
        Instant t = Instant.now().minusSeconds(7200);
        JsonNode day1 = sync(id("device"), List.of(
                event(1, "inspection.submitted", route1, null, t, "inspection",
                        inspection(route1, t, "VAN-1", 10_000, null)),
                event(2, "loadout.scanned", route1, null, t.plusSeconds(10), "scan", scan(label, false)),
                event(3, "loadout.completed", route1, null, t.plusSeconds(20)),
                event(4, "route.started", route1, null, t.plusSeconds(30)),
                event(5, "stop.arrived", route1, stop1, t.plusSeconds(600)),
                event(6, "customer.contacted", route1, stop1, t.plusSeconds(610), "contact", "CALL"),
                event(7, "customer.contacted", route1, stop1, t.plusSeconds(620), "contact", "TEXT"),
                event(8, "customer.contacted", route1, stop1, t.plusSeconds(630), "contact", "WAIT_STARTED"),
                event(9, "delivery.problem", route1, stop1, t.plusSeconds(940), "problem",
                        Map.of("type", "CUSTOMER_UNAVAILABLE", "resolution", "RETURN_TO_FC"))), "token-d1");
        assertThat(outcomes(day1)).allMatch("APPLIED"::equals);

        // ---- dispatch moves the failed stop to route 2
        TestSupport.as(TestSupport.dispatcher());
        JsonNode target = ok(send("POST", "/v1/dispatch/routes/" + route1 + "/stops/" + stop1 + "/reassign",
                Map.of("targetRouteId", route2)));
        assertThat(target.path("id").asString()).isEqualTo(route2);
        assertThat(target.path("packages").get(0).path("label").asString()).isEqualTo(label);
        assertThat(target.path("packages").get(0).path("origin").asString()).isEqualTo("FC");
        String stop2 = target.path("stops").get(0).path("id").asString();
        JsonNode source = ok(send("GET", "/v1/dispatch/routes/" + route1, null));
        assertThat(source.path("stops").get(0).path("status").asString()).isEqualTo("REASSIGNED");
        assertThat(source.path("stops").get(0).path("reassignedTo").asString()).isEqualTo(stop2);
        assertThat(outboxTypes(route1)).contains("fulfilment.stop.failed", "fulfilment.stop.reassigned");
        problem(send("POST", "/v1/dispatch/routes/" + route1 + "/stops/" + stop1 + "/reassign",
                Map.of("targetRouteId", route2)), 409, "fulfilment.stop-not-reassignable");

        // ---- driver 1 still scans the package back in and ends the shift
        TestSupport.as(TestSupport.driver(d1));
        JsonNode end1 = sync(id("device"), List.of(
                event(1, "return.scanned", route1, null, t.plusSeconds(3000), "scan", scan(label, false)),
                event(2, "shift.ended", route1, null, t.plusSeconds(3100), "odometer", 10_050)), "token-d1");
        assertThat(outcomes(end1)).containsExactly("APPLIED", "APPLIED");

        // ---- driver 2 delivers while orders-service is unreachable: applied, forwarded later in order
        UPSTREAMS.ordersDownFor(shipment);
        TestSupport.as(TestSupport.driver(d2));
        Instant t2 = t.plusSeconds(4000);
        Map<String, Object> pod = Map.of("handoverMethod", "CUSTOMER", "handoverCode", "4821",
                "photo", photo(route2, t2.plusSeconds(700), 29.7376, -95.5134));
        JsonNode day2 = sync(id("device"), List.of(
                event(1, "inspection.submitted", route2, null, t2, "inspection",
                        inspection(route2, t2, "VAN-2", 20_000, null)),
                event(2, "loadout.scanned", route2, null, t2.plusSeconds(10), "scan", scan(label, false)),
                event(3, "loadout.completed", route2, null, t2.plusSeconds(20)),
                event(4, "route.started", route2, null, t2.plusSeconds(30)),
                event(5, "stop.arrived", route2, stop2, t2.plusSeconds(600)),
                event(6, "stop.scanned", route2, stop2, t2.plusSeconds(610), "scan", scan(label, false)),
                event(7, "delivery.completed", route2, stop2, t2.plusSeconds(700), "delivery", pod)), "token-d2");
        assertThat(outcomes(day2)).containsExactly("APPLIED", "APPLIED", "APPLIED", "APPLIED", "APPLIED",
                "APPLIED", "APPLIED:fulfilment.forward-pending");
        assertThat(day2.path("results").get(6).path("forwardPending").asBoolean()).isTrue();
        assertThat(pending(shipment)).containsExactly("picked_up", "out_for_delivery", "delivered");

        UPSTREAMS.ordersUpFor(shipment);
        Map<String, Integer> drained = new HashMap<>();
        for (int i = 0; i < 5 && !pending(shipment).isEmpty(); i++) {
            jdbc.sql("update orders_command set next_attempt_at = now() - interval '1 minute' "
                    + "where shipment_id = :s and status = 'PENDING'").param("s", shipment).update();
            drained.merge("runs", relay.drain(50), Integer::sum);
        }
        assertThat(pending(shipment)).isEmpty();
        assertThat(jdbc.sql("select count(*) from orders_command where shipment_id = :s and status = 'DONE' "
                + "and jsonb_exists(courier, 'handoverCode')").param("s", shipment).query(Long.class).single())
                .as("the code is dropped once forwarded").isZero();
        assertThat(jdbc.sql("select status from delivery_shipment where shipment_id = :s").param("s", shipment)
                .query(String.class).single()).isEqualTo("DELIVERED");
    }

    private List<String> pending(String shipment) {
        return jdbc.sql("select target from orders_command where shipment_id = :s and status = 'PENDING' order by id")
                .param("s", shipment).query(String.class).list();
    }
}
