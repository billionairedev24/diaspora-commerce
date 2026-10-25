package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.PreorderFulfilment;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S264: what a delivery run needs from orders-service. A window's package keeps each line's seller (the placed event
 * says so), the run's driver moves a pickup-point pre-order shipment with courier events and the customer's code, and
 * the delivery starts the seller payout hold from the time the driver gave.
 */
class PreorderRunDeliveryIT extends OrdersIntegrationTest {

    private FollowUpService followUps;

    @BeforeEach
    void wire(@Autowired FollowUpService service) {
        this.followUps = service;
    }

    private String event(String eventId, String status, String at, String proof) {
        return "{\"eventId\":\"" + eventId + "\",\"status\":\"" + status + "\",\"occurredAt\":\"" + at + "\""
                + (proof == null ? "" : ",\"proof\":" + proof) + "}";
    }

    /** A paid pickup-point pre-order package of two sellers' lines, handover code 4821. */
    private Order placedPreorder(String customerId, String paymentId, PreorderFulfilment fulfilment) {
        String orderId = UUID.randomUUID().toString();
        String shipmentId = UUID.randomUUID().toString();
        DeliveryMethod method = fulfilment.method();
        OrderLine egusi = new OrderLine(UUID.randomUUID().toString(), 1, id("ATIN"), "P", "Egusi", null, 2, 1250, 103,
                0, "groceries", false, "US-TX", "FOOD", null, 0, "org-mama", "Mama Tee's Pantry");
        OrderLine oil = new OrderLine(UUID.randomUUID().toString(), 2, id("ATIN"), "P", "Palm oil", null, 1, 900, 74,
                0, "groceries", false, "US-TX", "FOOD", null, 0, "org-lagos", "Lagos Oils");
        boolean code = handover.required(false, method, DeliveryPreferences.defaults());
        Shipment shipment = new Shipment(shipmentId, orderId, 1, "org-mama", "Mama Tee's Pantry",
                FulfilmentType.PREORDER, null, method, "standard", method == DeliveryMethod.PICKUP ? "preorder:win-1"
                : null, DeliveryPreferences.defaults(), false, code, code ? handover.hash(shipmentId, "4821") : null,
                code ? vault.seal(shipmentId, "4821") : null, ShipmentStatus.PENDING_PAYMENT, 0, null, null,
                LocalDate.now(), LocalDate.now().plusDays(3), null, null, null, null, Instant.now(),
                List.of(egusi, oil), 0, null, PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7",
                LocalDate.now().plusDays(3), Instant.now().plusSeconds(86_400), fulfilment));
        Order order = new Order(orderId, "AT-2026-" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(Locale.ROOT), customerId, "USD", OrderStatus.PENDING_PAYMENT,
                OrderTotals.ofShipments(List.of(shipment)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Lubbock", "TX", "79401", "US", null), paymentId, "checkout-" + orderId, null, null,
                Instant.now(), List.of(shipment), 0);
        placement.persistPending(order, null);
        return placement.place(orderId);
    }

    @Test
    void thePlacedEventNamesEachLinesSellerAndTheLinesKeepThemInTheDatabase() {
        Order order = placedPreorder(id("cust"), UUID.randomUUID().toString(), PreorderFulfilment.PICKUP);

        List<String> sellers = jdbc.sql("select seller_org_id || '/' || seller_name from order_line "
                + "where order_id = :o order by line_no").param("o", UUID.fromString(order.id()))
                .query(String.class).list();
        assertThat(sellers).containsExactly("org-mama/Mama Tee's Pantry", "org-lagos/Lagos Oils");

        String payload = jdbc.sql("select payload::text from platform_outbox where type = 'orders.order.placed' "
                + "and aggregate_id = :id").param("id", order.id()).query(String.class).single();
        JsonNode lines = JSON.readTree(payload).path("shipments").get(0).path("lines");
        assertThat(lines).extracting(l -> l.path("sellerOrgId").asString()).containsExactly("org-mama", "org-lagos");
        assertThat(lines).extracting(l -> l.path("sellerName").asString())
                .containsExactly("Mama Tee's Pantry", "Lagos Oils");
        assertThat(JSON.readTree(payload).path("shipments").get(0).path("fulfilmentType").asString())
                .isEqualTo("PREORDER");
    }

    @Test
    void theRunsDriverDeliversAPickupPointPreorderWithTheCustomersCodeAndThePayoutHoldStartsFromThatTime()
            throws Exception {
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        Order order = placedPreorder(id("cust"), paymentId, PreorderFulfilment.PICKUP);
        String shipment = order.shipments().getFirst().id();
        assertThat(order.shipments().getFirst().handoverRequired()).as("a pickup point always asks the code").isTrue();

        // fulfilment confirms the shipment when it plans the run
        TestSupport.as(TestSupport.fulfilmentOps());
        transition(shipment, "confirmed", null, null);

        // the driver collects it from the sellers, drives, and hands it over at the pickup point
        TestSupport.as(TestSupport.driver());
        assertThat(ok(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                event("run-1", "picked_up", "2026-10-24T14:10:00Z", null))).path("outcome").asString())
                .isEqualTo("applied");
        ok(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                event("run-2", "out_for_delivery", "2026-10-24T14:11:00Z", null)));
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/capture")));

        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("run-3", "delivered",
                "2026-10-24T19:00:00Z", "{\"photoAssetId\":\"img-1\",\"handoverMethod\":\"customer\","
                        + "\"handoverCode\":\"0000\"}")), 422, "orders.handover-code-invalid");
        assertThat(status(shipment)).isEqualTo("OUT_FOR_DELIVERY");
        String delivered = event("run-4", "delivered", "2026-10-24T19:30:00Z", "{\"photoAssetId\":\"img-2\","
                + "\"handoverMethod\":\"customer\",\"handoverCode\":\"4821\"}");
        assertThat(ok(send("POST", "/v1/shipments/" + shipment + "/courier-events", delivered)).path("outcome")
                .asString()).isEqualTo("applied");
        assertThat(ok(send("POST", "/v1/shipments/" + shipment + "/courier-events", delivered)).path("outcome")
                .asString()).as("a repeated delivery changes nothing").isEqualTo("replayed");
        assertThat(status(shipment)).isEqualTo("DELIVERED");

        // the delivery event delivery-runs completes the run from, with the time the driver gave
        List<String> events = jdbc.sql("select payload::text from platform_outbox where type = "
                + "'orders.shipment.delivered' and aggregate_id = :id").param("id", order.id())
                .query(String.class).list();
        assertThat(events).hasSize(1);
        JsonNode event = JSON.readTree(events.getFirst());
        assertThat(event.path("fulfilmentType").asString()).isEqualTo("PREORDER");
        assertThat(Instant.parse(event.path("deliveredAt").asString())).isEqualTo(Instant.parse("2026-10-24T19:30:00Z"));
        List<String> moves = jdbc.sql("select payload ->> 'to' from platform_outbox where type = "
                + "'orders.shipment.status_changed' and aggregate_id = :id order by occurred_at")
                .param("id", order.id()).query(String.class).list();
        assertThat(moves).contains("confirmed", "picked_up", "out_for_delivery", "delivered");

        // payments starts the settlement hold of the seller net from that delivery
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/settlements/" + order.id()
                + "/delivered")).withRequestBody(WireMock.containing("\"deliveredAt\":\"2026-10-24T19:30:00Z\"")));
    }

    @Test
    void aDoorPreorderIsDeliveredTheSameWayAndOtherPickupShipmentsStillRefuseCouriers() {
        Order door = placedPreorder(id("cust"), UUID.randomUUID().toString(), PreorderFulfilment.DOOR);
        String shipment = door.shipments().getFirst().id();
        TestSupport.as(TestSupport.fulfilmentOps());
        transition(shipment, "confirmed", null, null);
        TestSupport.as(TestSupport.driver());
        assertThat(ok(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                event("door-1", "picked_up", "2026-10-24T14:10:00Z", null))).path("outcome").asString())
                .isEqualTo("applied");

        Order sellerPickup = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(id("org"), FulfilmentType.SELLER_FULFILLED, DeliveryMethod.PICKUP));
        String pickupShipment = sellerPickup.shipments().getFirst().id();
        TestSupport.as(TestSupport.driver());
        problem(send("POST", "/v1/shipments/" + pickupShipment + "/courier-events",
                event("pk-1", "picked_up", "2026-10-24T14:10:00Z", null)), 403, "orders.transition-forbidden");
    }
}
