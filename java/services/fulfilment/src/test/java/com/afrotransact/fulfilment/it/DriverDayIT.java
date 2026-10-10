package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A full driver day through the offline sync API: route builder, assignment, inspection with a critical defect,
 * load-out scans, seller pickup, proof of delivery checked by orders-service, customer-unavailable policy, return scan
 * and end of shift; then idempotent replay, a sequence gap and an SOS that jumps the queue to the live stream.
 */
class DriverDayIT extends FulfilmentIntegrationTest {

    private static final String BEARER = "driver-token-7";

    @Test
    void driverDayEndToEnd() {
        String driver = id("driver");
        String vehicle = id("TX");
        String order1 = UUID.randomUUID().toString();
        String order2 = UUID.randomUUID().toString();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        UPSTREAMS.reservation(order1, NODE, "ATVJOLLOF02");
        UPSTREAMS.reservation(order2, NODE, "ATVRICE0002");
        intake.orderPlaced(UUID.randomUUID(), order(order1, "77057",
                shipment(a, "FBA_AFROTRANSACT", "AFROTRANSACT_DELIVERY", true, false, "ATVJOLLOF02"),
                shipment(b, "SELLER_FULFILLED", "AFROTRANSACT_DELIVERY", true, true, "ATVTRAY0002")));
        intake.orderPlaced(UUID.randomUUID(), order(order2, "77081",
                shipment(c, "FBA_AFROTRANSACT", "AFROTRANSACT_DELIVERY", false, false, "ATVRICE0002")));
        jdbc.sql("update delivery_shipment set status = 'CANCELLED' where status = 'AWAITING_ROUTE' "
                + "and shipment_id not in (:ids)").param("ids", List.of(a, b, c)).update();

        // ---- dispatch: build per zone and assign
        TestSupport.as(TestSupport.dispatcher());
        JsonNode built = ok(send("POST", "/v1/dispatch/routes/build", Map.of("serviceDate", "2026-10-12",
                "depotNodeId", NODE, "maxDropoffs", 25, "window", "1:00–5:00 pm")));
        assertThat(built).hasSize(1);
        JsonNode route = built.get(0);
        String routeId = route.path("id").asString();
        assertThat(route.path("zone").asString()).isEqualTo("Houston SW");
        assertThat(route.path("perishable").asBoolean()).isTrue();
        ok(send("POST", "/v1/dispatch/routes/" + routeId + "/assign", Map.of("driverId", driver,
                "vehicleId", vehicle)));

        // ---- driver signs in and downloads the route
        TestSupport.as(TestSupport.driver(driver));
        JsonNode me = ok(send("GET", "/v1/driver/me", null, "Authorization", "Bearer " + BEARER));
        assertThat(me.path("routeIds").toString()).contains(routeId);
        JsonNode manifest = ok(send("GET", "/v1/driver/routes/" + routeId, null));
        List<String> kinds = new ArrayList<>();
        manifest.path("stops").forEach(s -> kinds.add(s.path("kind").asString()));
        assertThat(kinds).containsExactly("PICKUP", "DROPOFF", "DROPOFF");
        String pickup = manifest.path("stops").get(0).path("id").asString();
        String drop1 = stopOf(manifest, a);
        String drop2 = stopOf(manifest, c);
        Map<String, String> label = labels(manifest);

        String device = id("device");
        Instant t = Instant.now().minusSeconds(3600);

        // ---- inspection: brakes fail → route blocked, defect to dispatch
        JsonNode first = sync(device, List.of(
                event(1, "session.signed_in", null, null, t),
                event(2, "inspection.submitted", routeId, null, t.plusSeconds(60),
                        "inspection", inspection(routeId, t, vehicle, 48_222, 37.0, "BRAKES")),
                event(3, "route.started", routeId, null, t.plusSeconds(90))), BEARER);
        assertThat(outcomes(first)).containsExactly("APPLIED", "APPLIED", "REJECTED:fulfilment.route-blocked");
        assertThat(first.path("lastSequence").asLong()).isEqualTo(3);

        TestSupport.as(TestSupport.dispatcher());
        JsonNode defects = ok(send("GET", "/v1/dispatch/defects", null));
        JsonNode defect = null;
        for (JsonNode d : defects) {
            if (routeId.equals(d.path("routeId").asString())) {
                defect = d;
            }
        }
        assertThat(defect).isNotNull();
        assertThat(defect.path("critical").asBoolean()).isTrue();
        assertThat(defect.path("items").toString()).contains("BRAKES");
        ok(send("POST", "/v1/dispatch/defects/" + defect.path("id").asString() + "/resolve",
                Map.of("routeId", routeId, "note", "Brake pads replaced")));

        // ---- the rest of the day in one offline batch
        TestSupport.as(TestSupport.driver(driver));
        Instant tc = t.plusSeconds(3000);
        Map<String, Object> wrongPlace = Map.of("handoverMethod", "SAFE_PLACE", "safePlace", "Front door",
                "photo", photo(routeId, t.plusSeconds(2000), 29.7376, -95.5134));
        Map<String, Object> wrongCode = Map.of("handoverMethod", "CUSTOMER", "handoverCode", Upstreams.WRONG_CODE,
                "photo", photo(routeId, t.plusSeconds(2000), 29.7376, -95.5134));
        Map<String, Object> rightCode = Map.of("handoverMethod", "CUSTOMER", "handoverCode", "4821",
                "photo", photo(routeId, t.plusSeconds(2000), 29.7376, -95.5134));
        List<Map<String, Object>> day = List.of(
                event(4, "inspection.submitted", routeId, null, t.plusSeconds(600),
                        "inspection", inspection(routeId, t, vehicle, 48_222, 45.0)),
                event(5, "inspection.submitted", routeId, null, t.plusSeconds(660),
                        "inspection", inspection(routeId, t, vehicle, 48_222, 37.0)),
                event(6, "loadout.scanned", routeId, null, t.plusSeconds(700), "scan", scan("ATS000000000", false)),
                event(7, "loadout.scanned", routeId, null, t.plusSeconds(710), "scan", scan(label.get(a), true)),
                event(8, "loadout.scanned", routeId, null, t.plusSeconds(720), "scan", scan(label.get(a), false)),
                event(9, "loadout.scanned", routeId, null, t.plusSeconds(730), "scan", scan(label.get(c), false)),
                event(10, "loadout.completed", routeId, null, t.plusSeconds(740)),
                event(11, "route.started", routeId, null, t.plusSeconds(800)),
                event(12, "stop.arrived", routeId, pickup, t.plusSeconds(1500)),
                event(13, "stop.scanned", routeId, pickup, t.plusSeconds(1510), "scan", scan(label.get(b), false)),
                event(14, "pickup.confirmed", routeId, pickup, t.plusSeconds(1520),
                        "pickup", Map.of("handedOverBy", "Titi")),
                event(15, "stop.arrived", routeId, drop1, t.plusSeconds(1900)),
                event(16, "stop.scanned", routeId, drop1, t.plusSeconds(1910), "scan", scan(label.get(a), false)),
                event(17, "stop.scanned", routeId, drop1, t.plusSeconds(1920), "scan", scan(label.get(b), false)),
                event(18, "delivery.completed", routeId, drop1, t.plusSeconds(2010), "delivery", wrongPlace),
                event(19, "delivery.completed", routeId, drop1, t.plusSeconds(2020), "delivery", wrongCode),
                event(20, "delivery.completed", routeId, drop1, t.plusSeconds(2030), "delivery", rightCode),
                event(21, "stop.arrived", routeId, drop2, tc),
                event(22, "customer.contacted", routeId, drop2, tc.plusSeconds(5), "contact", "CALL"),
                event(23, "customer.contacted", routeId, drop2, tc.plusSeconds(20), "contact", "TEXT"),
                event(24, "customer.contacted", routeId, drop2, tc.plusSeconds(30), "contact", "WAIT_STARTED"),
                event(25, "delivery.problem", routeId, drop2, tc.plusSeconds(270), "problem",
                        Map.of("type", "CUSTOMER_UNAVAILABLE", "resolution", "RETURN_TO_FC")),
                event(26, "delivery.problem", routeId, drop2, tc.plusSeconds(330), "problem",
                        Map.of("type", "CUSTOMER_UNAVAILABLE", "resolution", "RETURN_TO_FC")),
                event(27, "shift.ended", routeId, null, tc.plusSeconds(3000), "odometer", 48_300),
                event(28, "return.scanned", routeId, null, tc.plusSeconds(3100), "scan", scan(label.get(c), false)),
                event(29, "shift.ended", routeId, null, tc.plusSeconds(3200), "odometer", 48_300));
        JsonNode result = sync(device, day, BEARER);
        assertThat(outcomes(result)).containsExactly(
                "REJECTED:fulfilment.cold-box-out-of-range", "APPLIED", "REJECTED:fulfilment.label-unknown",
                "APPLIED", "CONFLICT:fulfilment.label-already-scanned", "APPLIED", "APPLIED", "APPLIED", "APPLIED",
                "APPLIED", "APPLIED", "APPLIED", "APPLIED", "APPLIED",
                "REJECTED:fulfilment.safe-place-not-allowed", "REJECTED:orders.handover-code-invalid", "APPLIED",
                "APPLIED", "APPLIED", "APPLIED", "APPLIED", "REJECTED:fulfilment.contact-policy-not-met", "APPLIED",
                "REJECTED:fulfilment.returns-not-scanned", "APPLIED", "APPLIED");
        assertThat(result.path("lastSequence").asLong()).isEqualTo(29);
        assertThat(result.path("signOut").asBoolean()).isFalse();

        // ---- the outcome as dispatch sees it
        TestSupport.as(TestSupport.dispatcher());
        JsonNode done = ok(send("GET", "/v1/dispatch/routes/" + routeId, null));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        List<String> stopStatuses = new ArrayList<>();
        done.path("stops").forEach(s -> stopStatuses.add(s.path("status").asString()));
        assertThat(stopStatuses).containsExactly("DONE", "DONE", "FAILED");
        Map<String, JsonNode> pkgs = new HashMap<>();
        done.path("packages").forEach(p -> pkgs.put(p.path("shipmentId").asString(), p));
        assertThat(pkgs.get(a).path("manualEntry").asBoolean()).as("typed-in label is flagged").isTrue();
        assertThat(pkgs.get(a).path("status").asString()).isEqualTo("DELIVERED");
        assertThat(pkgs.get(b).path("status").asString()).isEqualTo("DELIVERED");
        assertThat(pkgs.get(c).path("status").asString()).isEqualTo("RETURNED");
        assertThat(done.path("stops").get(1).path("handoverMethod").asString()).isEqualTo("CUSTOMER");
        assertThat(done.path("stops").get(0).path("handedOverBy").asString()).isEqualTo("Titi");

        // ---- orders-service received the courier events with the driver's own token
        for (String[] step : new String[][] {{a, "picked_up"}, {a, "out_for_delivery"}, {a, "delivered"},
            {b, "picked_up"}, {b, "out_for_delivery"}, {b, "delivered"}, {c, "picked_up"}, {c, "delivery_failed"}}) {
            WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + step[0]
                            + "/courier-events"))
                    .withHeader("Authorization", WireMock.equalTo("Bearer " + BEARER))
                    .withRequestBody(WireMock.containing("\"status\":\"" + step[1] + "\"")));
        }
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + a + "/courier-events"))
                .withRequestBody(WireMock.containing("\"handoverCode\":\"4821\"")));
        // S258: look at the stored handover code field itself (a substring match on the payload also hit random ids).
        List<String> storedCodes = jdbc.sql("""
                        select payload -> 'delivery' ->> 'handoverCode' from driver_event
                        where route_id = :r and type = 'delivery.completed'
                          and payload -> 'delivery' ->> 'handoverCode' is not null
                        """).param("r", routeId).query(String.class).list();
        assertThat(storedCodes).as("handover codes are never stored").hasSizeGreaterThanOrEqualTo(2)
                .containsOnly("****");
        assertThat(outboxTypes(routeId)).containsSubsequence("fulfilment.route.planned", "fulfilment.route.assigned",
                "fulfilment.vehicle.defect_reported", "fulfilment.route.started", "fulfilment.stop.delivered",
                "fulfilment.stop.failed", "fulfilment.route.completed");
        assertThat(ok(send("GET", "/v1/dispatch/events?routeId=" + routeId + "&limit=100", null))).hasSize(28);

        // ---- the app resends the same batch (it never saw the response): stored outcomes, nothing re-applied
        TestSupport.as(TestSupport.driver(driver));
        JsonNode replay = sync(device, day, BEARER);
        assertThat(outcomes(replay)).isEqualTo(outcomes(result));
        replay.path("results").forEach(r -> assertThat(r.path("replayed").asBoolean()).isTrue());

        // ---- SOS jumps a sequence gap and reaches the dispatch stream at once
        TestSupport.as(TestSupport.dispatcher());
        BlockingQueue<String> stream = openStream("/v1/dispatch/stream");
        await(stream, "event:ready");
        TestSupport.as(TestSupport.driver(driver));
        Map<String, Object> sos = event(31, "sos.raised", routeId, null, tc.plusSeconds(4000), "sos", "ACCIDENT",
                "note", "Rear-ended at the light");
        JsonNode gap = sync(device, List.of(sos), BEARER);
        assertThat(outcomes(gap)).containsExactly("DEFERRED:fulfilment.sequence-gap");
        assertThat(gap.path("expectedSequence").asLong()).isEqualTo(30);
        List<String> lines = await(stream, "event:driver.sos");
        assertThat(String.join("\n", lines)).doesNotContain("error:");
        TestSupport.as(TestSupport.dispatcher());
        JsonNode alerts = ok(send("GET", "/v1/dispatch/sos", null));
        assertThat(alerts.toString()).contains(String.valueOf(sos.get("eventId"))).contains("ACCIDENT");

        TestSupport.as(TestSupport.driver(driver));
        JsonNode filled = sync(device, List.of(event(30, "session.signed_in", null, null, tc.plusSeconds(3990)),
                sos), BEARER);
        assertThat(outcomes(filled)).containsExactly("APPLIED", "APPLIED");
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'fulfilment.driver.sos_raised' "
                + "and payload ->> 'alertId' = :id").param("id", String.valueOf(sos.get("eventId")))
                .query(Long.class).single()).as("raised once").isEqualTo(1);
    }

    private static String stopOf(JsonNode route, String shipmentId) {
        for (JsonNode p : route.path("packages")) {
            if (shipmentId.equals(p.path("shipmentId").asString())) {
                return p.path("dropoffStopId").asString();
            }
        }
        throw new AssertionError("no package for " + shipmentId);
    }

    private static Map<String, String> labels(JsonNode route) {
        Map<String, String> out = new HashMap<>();
        route.path("packages").forEach(p -> out.put(p.path("shipmentId").asString(), p.path("label").asString()));
        return out;
    }
}
