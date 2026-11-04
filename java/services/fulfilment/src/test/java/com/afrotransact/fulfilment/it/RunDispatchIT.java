package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.fulfilment.application.OrdersRelayService;
import com.afrotransact.fulfilment.application.PlacedOrder;
import com.afrotransact.fulfilment.application.RunDispatchService;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.port.DeliveryRunsClient.RunPlan;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * S264 against PostgreSQL (PostGIS) with WireMock for delivery-runs, orders and geo: a confirmed run is planned into
 * balanced routes with the sellers' pickups before the drops (doorstep drops ordered by road distance, or one handover
 * per order at the community pickup point), kept out of the zone builder and the seller-pickup screens, confirmed in
 * orders, worked through the ordinary driver flow (every step forwarded to orders-service), replanned without
 * duplicates, and taken off the road when it is cancelled.
 */
@TestPropertySource(properties = {"at.fulfilment.runs.depot-node=HOU-FC1", "at.fulfilment.runs.route-capacity=8"})
class RunDispatchIT extends FulfilmentIntegrationTest {

    private static final String BEARER = "driver-token-run";
    private static final Instant WINDOW_START = Instant.parse("2026-10-24T14:00:00Z");
    private static final GeoPoint HUB = new GeoPoint(30.2672, -97.7431);
    /** The hub of the run whose roads geo knows (the stub matches it, so other runs fall back to straight lines). */
    private static final GeoPoint ROAD_HUB = new GeoPoint(30.3111, -97.7111);

    RunDispatchService runs;
    OrdersRelayService relay;

    @BeforeEach
    void wireRuns(@Autowired RunDispatchService runDispatch, @Autowired OrdersRelayService ordersRelay) {
        this.runs = runDispatch;
        this.relay = ordersRelay;
    }

    // ---- 1. planning ----------------------------------------------------------------------------------------------

    @Test
    void aConfirmedRunIsPlannedIntoBalancedRoutesSellerPickupsFirstDropsNearestFirst() {
        String runId = UUID.randomUUID().toString();
        List<String> shipments = new ArrayList<>();
        List<String> orderIds = new ArrayList<>();
        // six doorstep orders, two sellers each (two bags): shipment i is i*0.02 degrees further from the hub
        for (int i = 1; i <= 6; i++) {
            String order = UUID.randomUUID().toString();
            String shp = "shp-run-" + runId.substring(0, 6) + "-" + i;
            orderIds.add(order);
            shipments.add(shp);
            intake.orderPlaced(UUID.randomUUID(), runOrder(order, shp, 30.20 - i * 0.02, -97.40, false, "DOOR"));
        }
        // geo knows the roads: shipment 1 is the farthest, shipment 6 the nearest (the opposite of the straight line)
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/distance/batch"))
                        .withRequestBody(WireMock.containing("30.3111")).atPriority(1), 200,
                "{\"results\":[" + roads(60, 50, 40, 30, 20, 10) + "]}");
        stubRun(runId, orderIds, "DOORSTEP", null, ROAD_HUB);

        assertThat(shipmentsInPool()).as("pre-orders wait for their run, not for the zone builder")
                .doesNotContainAnyElementsOf(shipments);

        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, orderIds, true));

        TestSupport.as(TestSupport.dispatcher());
        JsonNode run = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        assertThat(run.path("status").asString()).isEqualTo("PLANNED");
        assertThat(run.path("town").asString()).isEqualTo("Bastrop");
        assertThat(run.path("serviceDate").asString()).isEqualTo("2026-10-24");
        assertThat(run.path("dropMode").asString()).isEqualTo("DOORSTEP");
        assertThat(run.path("depotNodeId").asString()).isEqualTo(NODE);
        assertThat(run.path("routes")).as("12 bags, room for 8: two balanced routes").hasSize(2);
        JsonNode first = run.path("routes").get(0);
        JsonNode second = run.path("routes").get(1);
        assertThat(first.path("packages").asInt()).isEqualTo(6);
        assertThat(second.path("packages").asInt()).isEqualTo(6);
        assertThat(first.path("name").asString()).isEqualTo("Bastrop run 1");
        assertThat(first.path("pickupStops").asInt()).isEqualTo(2);
        assertThat(first.path("dropStops").asInt()).isEqualTo(3);
        assertThat(run.path("progress").path("orders").asInt()).isEqualTo(6);
        assertThat(run.path("progress").path("bags").asInt()).isEqualTo(12);
        assertThat(run.path("progress").path("unrouted").asInt()).isZero();

        // sellers first (by name), then the drops, nearest by road first: shipments 6, 5, 4 then 3, 2, 1
        assertThat(kinds(first)).containsExactly("PICKUP", "PICKUP", "DROPOFF", "DROPOFF", "DROPOFF");
        assertThat(names(first).subList(0, 2)).containsExactly("Lagos Oils", "Mama Tee's Pantry");
        assertThat(dropShipments(first, shipments)).containsExactly(shipments.get(5), shipments.get(4),
                shipments.get(3));
        assertThat(dropShipments(second, shipments)).containsExactly(shipments.get(2), shipments.get(1),
                shipments.get(0));

        // planning is idempotent: the same event, a new event for the same run, and the staff button change nothing
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, orderIds, true));
        JsonNode again = ok(send("POST", "/v1/dispatch/runs/" + runId + "/plan", null));
        assertThat(again.path("routes")).hasSize(2);
        assertThat(count("select count(*) from run_route where run_id = ?", runId)).isEqualTo(2);
        assertThat(outboxTypes(runId)).filteredOn("fulfilment.run.planned"::equals).hasSize(1);

        // orders-service must see each shipment confirmed before the driver collects it
        assertThat(count("select count(*) from orders_command where kind = 'TRANSITION' and target = 'confirmed' "
                + "and shipment_id in (select shipment_id from run_shipment where run_id = ?)", runId)).isEqualTo(6);
        relay.drain(50);
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + shipments.get(0)
                        + "/transitions"))
                .withHeader("Idempotency-Key", WireMock.equalTo("fulfilment:run:" + runId + ":" + shipments.get(0)
                        + ":confirmed"))
                .withRequestBody(WireMock.containing("\"to\":\"confirmed\"")));

        // the list of runs carries the progress without the stops
        JsonNode list = ok(send("GET", "/v1/dispatch/runs?status=PLANNED&limit=100", null));
        JsonNode mine = find(list, "runId", runId);
        assertThat(mine.path("routes")).hasSize(2);
        assertThat(mine.path("routes").get(0).path("stops")).isEmpty();
        assertThat(ok(send("GET", "/v1/dispatch/runs?status=CANCELLED&limit=100", null)).toString())
                .doesNotContain(runId);
        problem(send("GET", "/v1/dispatch/runs/" + UUID.randomUUID(), null), 404, "fulfilment.run-not-found");
        assertThat(send("GET", "/v1/dispatch/runs?status=nonsense", null).statusCode()).isEqualTo(400);
    }

    @Test
    void preorderBagsAreOnePerSellerAndTheFirstCarriesTheShipmentsLabel() {
        String order = UUID.randomUUID().toString();
        String shp = "shp-bags-" + order.substring(0, 6);
        intake.orderPlaced(UUID.randomUUID(), runOrder(order, shp, 30.1, -97.3, false, "DOOR"));
        List<Map<String, Object>> bags = jdbc.sql("select seller_org_id, seller_name, label, units from shipment_bag "
                + "where shipment_id = :s order by seller_org_id").param("s", shp).query((rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("seller", rs.getString("seller_org_id"));
                    m.put("name", rs.getString("seller_name"));
                    m.put("label", rs.getString("label"));
                    m.put("units", rs.getInt("units"));
                    return m;
                }).list();
        assertThat(bags).hasSize(2);
        assertThat(bags).extracting(b -> b.get("seller")).containsExactly("org-lagos", "org-mama");
        assertThat(bags).extracting(b -> b.get("units")).containsExactly(1, 2);
        String shipmentLabel = jdbc.sql("select label from delivery_shipment where shipment_id = :s").param("s", shp)
                .query(String.class).single();
        assertThat(bags).extracting(b -> b.get("label")).contains(shipmentLabel).doesNotHaveDuplicates();
        assertThat(jdbc.sql("select fulfilment_type || '/' || origin || '/' || status from delivery_shipment "
                + "where shipment_id = :s").param("s", shp).query(String.class).single())
                .isEqualTo("PREORDER/SELLER/AWAITING_ROUTE");
        // the same order event again is harmless
        intake.orderPlaced(UUID.randomUUID(), runOrder(order, shp, 30.1, -97.3, false, "DOOR"));
        assertThat(count("select count(*) from shipment_bag where shipment_id = ?", shp)).isEqualTo(2);
    }

    // ---- 2. the driver works the run -------------------------------------------------------------------------------

    @Test
    void aDriverWorksARunAndEveryStepIsForwardedToOrders() {
        String runId = UUID.randomUUID().toString();
        String driver = id("driver");
        String vehicle = id("TX");
        String o1 = UUID.randomUUID().toString();
        String o2 = UUID.randomUUID().toString();
        String s1 = "shp-day-" + o1.substring(0, 6);
        String s2 = "shp-day-" + o2.substring(0, 6);
        intake.orderPlaced(UUID.randomUUID(), runOrder(o1, s1, 30.15, -97.35, true, "DOOR"));
        intake.orderPlaced(UUID.randomUUID(), runOrder(o2, s2, 30.14, -97.34, false, "DOOR"));
        stubRun(runId, List.of(o1, o2), "DOORSTEP", null);
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, List.of(o1, o2), true));
        // the relay job confirms the shipments in orders-service; until then a driver's events queue behind them
        relay.drain(50);

        TestSupport.as(TestSupport.dispatcher());
        JsonNode run = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        assertThat(run.path("routes")).hasSize(1);
        String routeId = run.path("routes").get(0).path("id").asString();
        ok(send("POST", "/v1/dispatch/routes/" + routeId + "/assign", Map.of("driverId", driver,
                "vehicleId", vehicle)));

        TestSupport.as(TestSupport.driver(driver));
        JsonNode manifest = ok(send("GET", "/v1/driver/routes/" + routeId, null));
        List<String> kinds = new ArrayList<>();
        manifest.path("stops").forEach(s -> kinds.add(s.path("kind").asString()));
        assertThat(kinds).containsExactly("PICKUP", "PICKUP", "DROPOFF", "DROPOFF");
        Map<String, List<String>> labelsAtPickup = new HashMap<>();
        Map<String, List<String>> labelsAtDrop = new HashMap<>();
        for (JsonNode p : manifest.path("packages")) {
            labelsAtPickup.computeIfAbsent(p.path("pickupStopId").asString(), k -> new ArrayList<>())
                    .add(p.path("label").asString());
            labelsAtDrop.computeIfAbsent(p.path("dropoffStopId").asString(), k -> new ArrayList<>())
                    .add(p.path("label").asString());
        }
        String pickupLagos = manifest.path("stops").get(0).path("id").asString();
        String pickupMama = manifest.path("stops").get(1).path("id").asString();
        String drop1 = manifest.path("stops").get(2).path("id").asString();
        String drop2 = manifest.path("stops").get(3).path("id").asString();

        String device = id("device");
        Instant t = Instant.now().minusSeconds(3600);
        List<Map<String, Object>> day = new ArrayList<>();
        long seq = 1;
        day.add(event(seq++, "inspection.submitted", routeId, null, t.plusSeconds(60), "inspection",
                inspection(routeId, t, vehicle, 51_000, 37.0)));
        day.add(event(seq++, "route.started", routeId, null, t.plusSeconds(120)));
        for (String pickup : List.of(pickupLagos, pickupMama)) {
            day.add(event(seq++, "stop.arrived", routeId, pickup, t.plusSeconds(600)));
            for (String label : labelsAtPickup.get(pickup)) {
                day.add(event(seq++, "stop.scanned", routeId, pickup, t.plusSeconds(610), "scan", scan(label, false)));
            }
            day.add(event(seq++, "pickup.confirmed", routeId, pickup, t.plusSeconds(620), "pickup",
                    Map.of("handedOverBy", "Seller staff")));
        }
        JsonNode morning = sync(device, day, BEARER);
        assertThat(outcomes(morning)).containsOnly("APPLIED");

        day = new ArrayList<>();
        for (String drop : List.of(drop1, drop2)) {
            day.add(event(seq++, "stop.arrived", routeId, drop, t.plusSeconds(1800)));
            for (String label : labelsAtDrop.get(drop)) {
                day.add(event(seq++, "stop.scanned", routeId, drop, t.plusSeconds(1810), "scan", scan(label, false)));
            }
        }
        // the photo is taken at the door (within 150 m of the address); the first order wants its customer's code
        // and the driver forgets it once. Which order is first depends on the roads, so the first drop is the one
        // that asks for the code.
        JsonNode at1 = stop(manifest, stop(manifest, drop1).path("handoverRequired").asBoolean() ? drop1 : drop2);
        JsonNode at2 = stop(manifest, at1.path("id").asString().equals(drop1) ? drop2 : drop1);
        String coded = at1.path("id").asString();
        String plain = at2.path("id").asString();
        Map<String, Object> noCode1 = Map.of("handoverMethod", "CUSTOMER",
                "photo", photo(routeId, t.plusSeconds(1900), at1.path("lat").asDouble(), at1.path("lon").asDouble()));
        Map<String, Object> withCode1 = Map.of("handoverMethod", "CUSTOMER", "handoverCode", "4821",
                "photo", photo(routeId, t.plusSeconds(1900), at1.path("lat").asDouble(), at1.path("lon").asDouble()));
        Map<String, Object> noCode2 = Map.of("handoverMethod", "CUSTOMER",
                "photo", photo(routeId, t.plusSeconds(1900), at2.path("lat").asDouble(), at2.path("lon").asDouble()));
        day.add(event(seq++, "delivery.completed", routeId, coded, t.plusSeconds(1900), "delivery", noCode1));
        day.add(event(seq++, "delivery.completed", routeId, coded, t.plusSeconds(1910), "delivery", withCode1));
        day.add(event(seq++, "delivery.completed", routeId, plain, t.plusSeconds(1950), "delivery", noCode2));
        JsonNode afternoon = sync(device, day, BEARER);
        List<String> outcomes = outcomes(afternoon);
        assertThat(outcomes.subList(outcomes.size() - 3, outcomes.size())).containsExactly(
                "REJECTED:fulfilment.handover-code-required", "APPLIED", "APPLIED");

        // orders-service saw every step of both shipments, with the driver's own token
        for (String shipment : List.of(s1, s2)) {
            for (String step : List.of("picked_up", "out_for_delivery", "delivered")) {
                WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + shipment
                                + "/courier-events"))
                        .withHeader("Authorization", WireMock.equalTo("Bearer " + BEARER))
                        .withRequestBody(WireMock.containing("\"status\":\"" + step + "\"")));
            }
        }
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + s1 + "/courier-events"))
                .withRequestBody(WireMock.containing("\"handoverCode\":\"4821\"")));

        // dispatch sees the run's progress
        TestSupport.as(TestSupport.dispatcher());
        JsonNode done = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        JsonNode progress = done.path("progress");
        assertThat(progress.path("pickupsDone").asInt()).isEqualTo(2);
        assertThat(progress.path("bagsCollected").asInt()).isEqualTo(4);
        assertThat(progress.path("dropsDone").asInt()).isEqualTo(2);
        assertThat(progress.path("bagsDelivered").asInt()).isEqualTo(4);
        assertThat(done.path("routes").get(0).path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(count("select count(*) from delivery_shipment where status = 'DELIVERED' and shipment_id in "
                + "(select shipment_id from run_shipment where run_id = ?)", runId)).isEqualTo(2);
    }

    @Test
    void aPickupPointRunMakesOneDropPerOrderAtThePointAndEachNeedsTheCustomersCode() {
        String runId = UUID.randomUUID().toString();
        String driver = id("driver");
        String vehicle = id("TX");
        String o1 = UUID.randomUUID().toString();
        String s1 = "shp-pp-" + o1.substring(0, 6);
        // a PICKUP shipment (handover required: orders always asks the customer's code at a pickup point)
        intake.orderPlaced(UUID.randomUUID(), runOrder(o1, s1, 30.15, -97.35, true, "PICKUP"));
        stubRun(runId, List.of(o1), "PICKUP_POINT", """
                {"name":"Bastrop Public Library","line1":"1100 Church St","city":"Bastrop","state":"TX",
                 "postalCode":"78602","hours":"Sat 2-5 PM","notes":"Side entrance"}""");
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, List.of(o1), false));
        relay.drain(50);

        TestSupport.as(TestSupport.dispatcher());
        JsonNode run = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        assertThat(run.path("dropMode").asString()).isEqualTo("PICKUP_POINT");
        assertThat(run.path("pickupPoint").path("name").asString()).isEqualTo("Bastrop Public Library");
        String routeId = run.path("routes").get(0).path("id").asString();
        JsonNode drop = null;
        for (JsonNode s : run.path("routes").get(0).path("stops")) {
            if ("DROPOFF".equals(s.path("kind").asString())) {
                drop = s;
            }
        }
        assertThat(drop).isNotNull();
        assertThat(drop.path("addressLine").asString()).isEqualTo("Bastrop Public Library, 1100 Church St");
        assertThat(drop.path("handoverRequired").asBoolean()).isTrue();
        assertThat(drop.path("safePlaceAllowed").asBoolean()).isFalse();
        ok(send("POST", "/v1/dispatch/routes/" + routeId + "/assign", Map.of("driverId", driver,
                "vehicleId", vehicle)));

        TestSupport.as(TestSupport.driver(driver));
        JsonNode manifest = ok(send("GET", "/v1/driver/routes/" + routeId, null));
        List<String> pickups = new ArrayList<>();
        String dropId = null;
        for (JsonNode st : manifest.path("stops")) {
            if ("PICKUP".equals(st.path("kind").asString())) {
                pickups.add(st.path("id").asString());
            } else {
                dropId = st.path("id").asString();
            }
        }
        assertThat(pickups).as("one pickup per seller of the order's two bags").hasSize(2);
        Map<String, List<String>> labelsAtPickup = new HashMap<>();
        List<String> labels = new ArrayList<>();
        for (JsonNode p : manifest.path("packages")) {
            labelsAtPickup.computeIfAbsent(p.path("pickupStopId").asString(), k -> new ArrayList<>())
                    .add(p.path("label").asString());
            labels.add(p.path("label").asString());
        }
        Instant t = Instant.now().minusSeconds(3600);
        String device = id("device");
        long seq = 1;
        List<Map<String, Object>> day = new ArrayList<>();
        day.add(event(seq++, "inspection.submitted", routeId, null, t, "inspection",
                inspection(routeId, t, vehicle, 52_000, 37.0)));
        day.add(event(seq++, "route.started", routeId, null, t.plusSeconds(10)));
        for (String pickup : pickups) {
            day.add(event(seq++, "stop.arrived", routeId, pickup, t.plusSeconds(100)));
            for (String label : labelsAtPickup.get(pickup)) {
                day.add(event(seq++, "stop.scanned", routeId, pickup, t.plusSeconds(110), "scan", scan(label, false)));
            }
            day.add(event(seq++, "pickup.confirmed", routeId, pickup, t.plusSeconds(120), "pickup",
                    Map.of("handedOverBy", "Seller staff")));
        }
        day.add(event(seq++, "stop.arrived", routeId, dropId, t.plusSeconds(900)));
        for (String label : labels) {
            day.add(event(seq++, "stop.scanned", routeId, dropId, t.plusSeconds(910), "scan", scan(label, false)));
        }
        // no code, a wrong code, the right code: the customer's 4-digit code is checked by orders-service
        day.add(event(seq++, "delivery.completed", routeId, dropId, t.plusSeconds(1000), "delivery",
                Map.of("handoverMethod", "CUSTOMER", "photo", photo(routeId, t.plusSeconds(990), 30.1, -97.3))));
        day.add(event(seq++, "delivery.completed", routeId, dropId, t.plusSeconds(1010), "delivery",
                Map.of("handoverMethod", "CUSTOMER", "handoverCode", Upstreams.WRONG_CODE,
                        "photo", photo(routeId, t.plusSeconds(1000), 30.1, -97.3))));
        day.add(event(seq++, "delivery.completed", routeId, dropId, t.plusSeconds(1020), "delivery",
                Map.of("handoverMethod", "CUSTOMER", "handoverCode", "7315",
                        "photo", photo(routeId, t.plusSeconds(1010), 30.1, -97.3))));
        List<String> outcomes = outcomes(sync(device, day, BEARER));
        assertThat(outcomes.subList(0, outcomes.size() - 3)).containsOnly("APPLIED");
        assertThat(outcomes.subList(outcomes.size() - 3, outcomes.size())).containsExactly(
                "REJECTED:fulfilment.handover-code-required", "REJECTED:orders.handover-code-invalid", "APPLIED");
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + s1 + "/courier-events"))
                .withRequestBody(WireMock.containing("\"status\":\"delivered\""))
                .withRequestBody(WireMock.containing("\"handoverCode\":\"7315\"")));
    }

    // ---- 3. late orders, cancelled orders, a cancelled run, delivery-runs down ---------------------------------------

    @Test
    void anOrderThatArrivesAfterPlanningGetsItsOwnRouteOnReplanAndCancelledShipmentsLeaveTheirRoute() {
        String runId = UUID.randomUUID().toString();
        String o1 = UUID.randomUUID().toString();
        String s1 = "shp-late-" + o1.substring(0, 6);
        intake.orderPlaced(UUID.randomUUID(), runOrder(o1, s1, 30.15, -97.35, false, "DOOR"));
        stubRun(runId, List.of(o1), "DOORSTEP", null);
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, List.of(o1), true));
        assertThat(count("select count(*) from run_route where run_id = ?", runId)).isEqualTo(1);

        String o2 = UUID.randomUUID().toString();
        String s2 = "shp-late-" + o2.substring(0, 6);
        intake.orderPlaced(UUID.randomUUID(), runOrder(o2, s2, 30.16, -97.36, false, "DOOR"));
        stubRun(runId, List.of(o1, o2), "DOORSTEP", null);
        TestSupport.as(TestSupport.dispatcher());
        JsonNode replanned = ok(send("POST", "/v1/dispatch/runs/" + runId + "/plan", null));
        assertThat(replanned.path("routes")).hasSize(2);
        assertThat(replanned.path("routes").get(1).path("name").asString()).isEqualTo("Bastrop run 2");
        assertThat(replanned.path("progress").path("orders").asInt()).isEqualTo(2);

        // orders-service cancels the first order's shipment: its drop and pickups leave the planned route
        intake.shipmentCancelled(UUID.randomUUID(), s1, "Customer cancelled");
        JsonNode afterCancel = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        JsonNode route1 = afterCancel.path("routes").get(0);
        assertThat(route1.path("dropStops").asInt()).isZero();
        assertThat(route1.path("pickupStops").asInt()).isZero();
        assertThat(afterCancel.path("routes").get(1).path("dropStops").asInt()).isEqualTo(1);
    }

    @Test
    void aCancelledRunTakesItsUnstartedRoutesOffTheRoadAndLeavesAStartedOne() {
        String runId = UUID.randomUUID().toString();
        List<String> orderIds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String order = UUID.randomUUID().toString();
            orderIds.add(order);
            intake.orderPlaced(UUID.randomUUID(), runOrder(order, "shp-can-" + runId.substring(0, 6) + "-" + i,
                    30.20 - i * 0.02, -97.40, false, "DOOR"));
        }
        stubRun(runId, orderIds, "DOORSTEP", null);
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, orderIds, true));
        List<String> routeIds = jdbc.sql("select route_id from run_route where run_id = :r order by seq")
                .param("r", runId).query(String.class).list();
        assertThat(routeIds).hasSize(2);
        jdbc.sql("update delivery_route set status = 'IN_PROGRESS' where id = :id").param("id", routeIds.get(0))
                .update();

        UUID event = UUID.randomUUID();
        runs.onRunCancelled(event, runId, "Storm");
        runs.onRunCancelled(event, runId, "Storm");
        runs.onRunCancelled(UUID.randomUUID(), runId, "Storm");
        assertThat(routeStatus(routeIds.get(0))).as("a driver already on the road finishes or is reassigned")
                .isEqualTo("IN_PROGRESS");
        assertThat(routeStatus(routeIds.get(1))).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select status from run_dispatch where run_id = :r").param("r", runId)
                .query(String.class).single()).isEqualTo("CANCELLED");
        // a cancelled run is never planned again
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, orderIds, true));
        assertThat(jdbc.sql("select status from run_dispatch where run_id = :r").param("r", runId)
                .query(String.class).single()).isEqualTo("CANCELLED");
        // a run fulfilment never heard of is ignored
        runs.onRunCancelled(UUID.randomUUID(), UUID.randomUUID().toString(), "unknown");
    }

    @Test
    void whenDeliveryRunsCannotBeReachedTheRunIsKeptForStaffToPlanLater() {
        String runId = UUID.randomUUID().toString();
        String o1 = UUID.randomUUID().toString();
        intake.orderPlaced(UUID.randomUUID(), runOrder(o1, "shp-down-" + o1.substring(0, 6), 30.15, -97.35, false,
                "DOOR"));
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/service/runs/" + runId + "/dispatch")).atPriority(1),
                503, "{\"code\":\"delivery-runs.unavailable\"}");

        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, List.of(o1), true));

        TestSupport.as(TestSupport.dispatcher());
        JsonNode kept = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        assertThat(kept.path("status").asString()).isEqualTo("PENDING");
        assertThat(kept.path("planError").asString()).contains("delivery-runs did not answer");
        assertThat(kept.path("routes")).isEmpty();
        assertThat(send("POST", "/v1/dispatch/runs/" + runId + "/plan", null).statusCode()).isEqualTo(503);

        stubRun(runId, List.of(o1), "DOORSTEP", null);
        JsonNode planned = ok(send("POST", "/v1/dispatch/runs/" + runId + "/plan", null));
        assertThat(planned.path("status").asString()).isEqualTo("PLANNED");
        assertThat(planned.path("planError").asString("")).isEmpty();
        assertThat(planned.path("routes")).hasSize(1);
    }

    @Test
    void aRunWhoseOrdersAreNotInDispatchYetWaitsWithAReason() {
        String runId = UUID.randomUUID().toString();
        String ghost = UUID.randomUUID().toString();
        stubRun(runId, List.of(ghost), "DOORSTEP", null);
        runs.onRunConfirmed(UUID.randomUUID(), fallback(runId, List.of(ghost), true));
        TestSupport.as(TestSupport.dispatcher());
        JsonNode waiting = ok(send("GET", "/v1/dispatch/runs/" + runId, null));
        assertThat(waiting.path("status").asString()).isEqualTo("PENDING");
        assertThat(waiting.path("planError").asString()).contains("None of the run's orders are in dispatch yet");
    }

    @Test
    void onlyDispatchersPlanRuns() {
        TestSupport.as(TestSupport.driver(id("driver")));
        assertThat(send("POST", "/v1/dispatch/runs/" + UUID.randomUUID() + "/plan", null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/dispatch/runs", null).statusCode()).isEqualTo(403);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** A pre-order shipment of one order: 2 egusi from Mama Tee's and 1 palm oil from Lagos Oils. */
    private PlacedOrder runOrder(String orderId, String shipmentId, double lat, double lon, boolean handover,
                                 String fulfilment) {
        PlacedOrder.PlacedShipment shipment = new PlacedOrder.PlacedShipment(shipmentId, "org-mama",
                "Mama Tee's Pantry", "PREORDER", "PICKUP".equals(fulfilment) ? "PICKUP" : "AFROTRANSACT_DELIVERY",
                LocalDate.of(2026, 10, 24), handover, false, null,
                List.of(new PlacedOrder.PlacedLine(UUID.randomUUID().toString(), "EGU", "Egusi", 2, "org-mama",
                                "Mama Tee's Pantry"),
                        new PlacedOrder.PlacedLine(UUID.randomUUID().toString(), "PAL", "Palm oil", 1, "org-lagos",
                                "Lagos Oils")),
                null, null);
        return new PlacedOrder(orderId, "AT-2026-" + orderId.substring(orderId.length() - 6), "cust-1", "Bastrop",
                "TX", "78602", "US", lat, lon, List.of(shipment));
    }

    private void stubRun(String runId, List<String> orders, String dropMode, String pickupPoint) {
        stubRun(runId, orders, dropMode, pickupPoint, HUB);
    }

    private void stubRun(String runId, List<String> orders, String dropMode, String pickupPoint, GeoPoint hub) {
        StringBuilder list = new StringBuilder();
        for (String o : orders) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append("{\"orderId\":\"").append(o).append("\",\"status\":\"CONFIRMED\",\"stage\":\"PENDING\"}");
        }
        String body = "{\"run\":{\"id\":\"" + runId + "\",\"status\":\"CONFIRMED\",\"windowStart\":\"" + WINDOW_START
                + "\",\"windowEnd\":\"" + WINDOW_START.plusSeconds(43_200) + "\"},\"location\":{\"name\":\"Bastrop\","
                + "\"state\":\"TX\",\"timeZone\":\"America/Chicago\",\"originLat\":" + hub.lat() + ",\"originLng\":"
                + hub.lon() + "},\"dropMode\":\"" + dropMode + "\",\"pickupPoint\":"
                + (pickupPoint == null ? "null" : pickupPoint) + ",\"sellerPickupAt\":\""
                + WINDOW_START.minusSeconds(86_400) + "\",\"orders\":[" + list + "]}";
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/service/runs/" + runId + "/dispatch"))
                .atPriority(0), 200, body);
    }

    /** What the confirmed event alone says (used when delivery-runs cannot be asked). */
    private static RunPlan fallback(String runId, List<String> orders, boolean doorstep) {
        return new RunPlan(runId, "CONFIRMED", "Bastrop", "TX", "America/Chicago", WINDOW_START,
                WINDOW_START.plusSeconds(43_200), WINDOW_START.minusSeconds(86_400), doorstep, null, null, orders);
    }

    private static String roads(double... miles) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < miles.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"index\":").append(i).append(",\"roadMiles\":").append(miles[i])
                    .append(",\"durationMinutes\":").append(miles[i] * 1.5).append(",\"method\":\"ROUTING\"}");
        }
        return out.toString();
    }

    private List<String> shipmentsInPool() {
        List<String> ids = new ArrayList<>();
        TestSupport.as(TestSupport.dispatcher());
        ok(send("GET", "/v1/dispatch/shipments?limit=500", null)).forEach(s -> ids.add(s.path("shipmentId").asString()));
        ok(send("GET", "/v1/dispatch/pickups?limit=500", null)).forEach(s -> ids.add(
                s.path("shipment").path("shipmentId").asString()));
        return ids;
    }

    private static List<String> kinds(JsonNode route) {
        List<String> out = new ArrayList<>();
        route.path("stops").forEach(s -> out.add(s.path("kind").asString()));
        return out;
    }

    private static List<String> names(JsonNode route) {
        List<String> out = new ArrayList<>();
        route.path("stops").forEach(s -> out.add(s.path("name").asString()));
        return out;
    }

    /** The shipments of a route's drop-offs in visiting order (a drop is named after its order number). */
    private List<String> dropShipments(JsonNode route, List<String> shipments) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : route.path("stops")) {
            if ("DROPOFF".equals(s.path("kind").asString())) {
                String order = s.path("orderNumber").asString();
                out.add(jdbc.sql("select shipment_id from delivery_shipment where order_number = :n "
                        + "and shipment_id in (:ids)").param("n", order).param("ids", shipments)
                        .query(String.class).single());
            }
        }
        return out;
    }

    private static JsonNode stop(JsonNode route, String stopId) {
        return find(route.path("stops"), "id", stopId);
    }

    private static JsonNode find(JsonNode list, String field, String value) {
        for (JsonNode n : list) {
            if (value.equals(n.path(field).asString())) {
                return n;
            }
        }
        throw new AssertionError("No " + field + "=" + value + " in " + list);
    }

    private int count(String sql, String arg) {
        return jdbc.sql(sql.replace("?", ":p")).param("p", arg).query(Integer.class).single();
    }

    private String routeStatus(String routeId) {
        return jdbc.sql("select status from delivery_route where id = :id").param("id", routeId).query(String.class)
                .single();
    }
}
