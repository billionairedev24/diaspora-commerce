package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.fulfilment.application.PlacedOrder;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S235 return pickups scheduled by tax-returns and collected on RETURN_PICKUP stops; S229 ship-by data published when
 * a seller hands a shipment to the driver (fulfilment.shipment.handed_over).
 */
class ReturnPickupIT extends FulfilmentIntegrationTest {

    private static final String BEARER = "driver-token-rp";
    private static final Actor TAX_RETURNS = new Actor("svc-tax-returns", PrincipalType.SERVICE, Optional.empty(),
            Set.of(), Set.of("delivery:returns"));

    private static Map<String, Object> pickupRequest(String rmaId, String rmaNumber) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("name", "Ada Obi");
        address.put("phone", "+17135550142");
        address.put("line1", "1 Main St");
        address.put("city", "Houston");
        address.put("state", "TX");
        address.put("postalCode", "77057");
        address.put("country", "US");
        address.put("lat", 29.7376);
        address.put("lon", -95.5134);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rmaId", rmaId);
        body.put("rmaNumber", rmaNumber);
        body.put("orderId", UUID.randomUUID().toString());
        body.put("orderNumber", "AT-2026-RTN001");
        body.put("customerId", "cust-1");
        body.put("sellerOrgId", "org-mama-tee");
        body.put("address", address);
        body.put("depotNodeId", NODE);
        body.put("collectBy", LocalDate.now().plusDays(5).toString());
        body.put("units", 2);
        return body;
    }

    @Test
    void returnPickupsAreScheduledRoutedAndCollectedAndSellerHandoversCarryShipBy() {
        String rmaId = UUID.randomUUID().toString();
        TestSupport.as(TAX_RETURNS);
        JsonNode scheduled = ok(send("POST", "/v1/return-pickups", pickupRequest(rmaId, "RMA-RP000001")), 201);
        String pickupId = scheduled.path("id").asString();
        assertThat(scheduled.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(scheduled.path("depotNodeId").asString()).isEqualTo(NODE);
        JsonNode replay = ok(send("POST", "/v1/return-pickups", pickupRequest(rmaId, "RMA-RP000001")), 200);
        assertThat(replay.path("id").asString()).isEqualTo(pickupId);
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'fulfilment.return_pickup.scheduled' "
                + "and aggregate_id = :id").param("id", rmaId).query(Long.class).single()).isEqualTo(1);
        TestSupport.as(TestSupport.driver("d-1"));
        problem(send("POST", "/v1/return-pickups", pickupRequest(UUID.randomUUID().toString(), "RMA-RP000009")),
                403, "auth.missing-permission");

        // a seller-fulfilled AfroTransact Delivery shipment whose ship-by date has passed
        String orderId = UUID.randomUUID().toString();
        String shipmentId = UUID.randomUUID().toString();
        intake.orderPlaced(UUID.randomUUID(), order(orderId, "77057", new PlacedOrder.PlacedShipment(shipmentId,
                "org-mama-tee", "Mama Tee's Pantry", "SELLER_FULFILLED", "AFROTRANSACT_DELIVERY",
                LocalDate.now().plusDays(1), false, false, null, List.of(new PlacedOrder.PlacedLine(
                UUID.randomUUID().toString(), "ATVSOUP0001", "Egusi soup base", 1)), LocalDate.now().minusDays(2))));
        jdbc.sql("update delivery_shipment set status = 'CANCELLED' where status = 'AWAITING_ROUTE' "
                + "and shipment_id <> :id").param("id", shipmentId).update();

        TestSupport.as(TestSupport.dispatcher());
        JsonNode pool = ok(send("GET", "/v1/return-pickups?status=REQUESTED&nodeId=" + NODE, null));
        assertThat(pool.toString()).contains(pickupId);
        JsonNode built = ok(send("POST", "/v1/dispatch/routes/build", Map.of("serviceDate", "2026-10-13",
                "depotNodeId", NODE, "maxDropoffs", 25, "window", "1:00–5:00 pm")));
        String routeId = built.get(0).path("id").asString();
        JsonNode withReturn = ok(send("POST", "/v1/dispatch/routes/" + routeId + "/return-pickups",
                Map.of("returnPickupIds", List.of(pickupId))));
        List<String> kinds = new ArrayList<>();
        withReturn.path("stops").forEach(s -> kinds.add(s.path("kind").asString()));
        assertThat(kinds).containsExactly("PICKUP", "DROPOFF", "RETURN_PICKUP");
        String returnStop = withReturn.path("stops").get(2).path("id").asString();
        String sellerStop = withReturn.path("stops").get(0).path("id").asString();
        String label = withReturn.path("packages").get(0).path("label").asString();
        TestSupport.as(TAX_RETURNS);
        assertThat(ok(send("GET", "/v1/return-pickups/" + pickupId, null)).path("status").asString())
                .isEqualTo("ROUTED");

        String driver = id("driver");
        String vehicle = id("TX");
        TestSupport.as(TestSupport.dispatcher());
        ok(send("POST", "/v1/dispatch/routes/" + routeId + "/assign", Map.of("driverId", driver,
                "vehicleId", vehicle)));
        TestSupport.as(TestSupport.driver(driver));
        Instant t = Instant.now().minusSeconds(3600);
        String device = id("device");
        JsonNode result = sync(device, List.of(
                event(1, "inspection.submitted", routeId, null, t, "inspection",
                        inspection(routeId, t, vehicle, 10_000, 37.0)),
                event(2, "loadout.completed", routeId, null, t.plusSeconds(60)),
                event(3, "route.started", routeId, null, t.plusSeconds(120)),
                event(4, "stop.arrived", routeId, sellerStop, t.plusSeconds(600)),
                event(5, "stop.scanned", routeId, sellerStop, t.plusSeconds(610), "scan", scan(label, false)),
                event(6, "pickup.confirmed", routeId, sellerStop, t.plusSeconds(620),
                        "pickup", Map.of("handedOverBy", "Titi")),
                event(7, "stop.arrived", routeId, returnStop, t.plusSeconds(900)),
                event(8, "return_pickup.failed", routeId, returnStop, t.plusSeconds(905), "note", "No"),
                event(9, "return_pickup.collected", routeId, returnStop, t.plusSeconds(910))), BEARER);
        assertThat(outcomes(result)).containsExactly("APPLIED", "APPLIED", "APPLIED", "APPLIED", "APPLIED",
                "APPLIED", "APPLIED", "REJECTED:fulfilment.note-required", "APPLIED");

        assertThat(jdbc.sql("""
                select (payload ->> 'origin') || '|' || (payload ->> 'late') || '|' || (payload ->> 'shipBy')
                from platform_outbox where type = 'fulfilment.shipment.handed_over' and aggregate_id = :id
                """).param("id", shipmentId).query(String.class).single())
                .isEqualTo("SELLER|true|" + LocalDate.now().minusDays(2));
        TestSupport.as(TAX_RETURNS);
        assertThat(ok(send("GET", "/v1/return-pickups/" + pickupId, null)).path("status").asString())
                .isEqualTo("COLLECTED");
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'fulfilment.return_pickup.collected' "
                + "and aggregate_id = :id").param("id", rmaId).query(Long.class).single()).isEqualTo(1);
        problem(send("POST", "/v1/return-pickups/" + pickupId + "/cancel", Map.of("reason", "Too late")), 409,
                "fulfilment.return-pickup-closed");
    }

    @Test
    void cancelledReturnsLeaveThePool() {
        String rmaId = UUID.randomUUID().toString();
        TestSupport.as(TAX_RETURNS);
        String pickupId = ok(send("POST", "/v1/return-pickups", pickupRequest(rmaId, "RMA-RP000002")), 201)
                .path("id").asString();
        JsonNode cancelled = ok(send("POST", "/v1/return-pickups/" + pickupId + "/cancel",
                Map.of("reason", "Customer cancelled the return")));
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(ok(send("POST", "/v1/return-pickups/" + pickupId + "/cancel", null)).path("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'fulfilment.return_pickup.cancelled' "
                + "and aggregate_id = :id").param("id", rmaId).query(Long.class).single()).isEqualTo(1);
    }
}
