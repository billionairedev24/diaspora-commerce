package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Actor rules of the shipment state machine over HTTP (architecture §17c "Who can do what"). */
class ShipmentRulesIT extends OrdersIntegrationTest {

    private FollowUpService followUps;

    @BeforeEach
    void wire(@Autowired FollowUpService service) {
        this.followUps = service;
    }

    @Test
    void sellerPreparesButCannotMarkDelivered() {
        String org = id("org");
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(org, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        String shipment = order.shipments().getFirst().id();
        TestSupport.as(TestSupport.seller(org));

        for (String step : new String[] {"confirmed", "packed", "ready"}) {
            JsonNode r = transition(shipment, step, null, null);
            assertThat(r.path("outcome").asString()).isEqualTo("applied");
            assertThat(r.path("order").path("shipments").get(0).path("status").asString()).isEqualTo(step);
        }
        problem(transitionRaw(shipment, "delivered"), 403, "orders.transition-forbidden");
        problem(transitionRaw(shipment, "picked_up"), 403, "orders.transition-forbidden");
        assertThat(status(shipment)).isEqualTo("READY");

        TestSupport.as(TestSupport.seller(id("org-other")));
        problem(transitionRaw(shipment, "packed"), 404, "orders.shipment-not-found");
    }

    @Test
    void adminsNeverMarkASellerFulfilledShipmentPackedButMayCancelWithAReason() {
        String org = id("org");
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(org, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        String shipment = order.shipments().getFirst().id();
        TestSupport.as(TestSupport.seller(org));
        transition(shipment, "confirmed", null, null);

        TestSupport.as(TestSupport.admin());
        JsonNode refused = problem(transitionRaw(shipment, "packed"), 403, "orders.transition-forbidden");
        assertThat(refused.path("detail").asString()).contains("Platform admins may not set a seller-fulfilled");
        problem(transitionRaw(shipment, "confirmed"), 403, "orders.transition-forbidden");
        problem(transitionRaw(shipment, "cancelled"), 422, "orders.reason-required");
        JsonNode cancelled = transition(shipment, "cancelled", "Seller out of stock", null);
        assertThat(cancelled.path("outcome").asString()).isEqualTo("applied");
        assertThat(cancelled.path("order").path("status").asString()).isEqualTo("cancelled");
        assertThat(outbox("orders.shipment.cancelled", order.id())).isEqualTo(1);
        assertThat(outbox("orders.order.cancelled", order.id())).isEqualTo(1);

        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + order.paymentId()
                + "/cancel")));
    }

    @Test
    void afrotransactFulfilledShipmentsAreRunByFulfilmentOpsAndCaptureOnPickup() {
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        Order order = placedOrder(id("cust"), paymentId,
                spec(id("org-fba"), FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER));
        String shipment = order.shipments().getFirst().id();

        TestSupport.as(TestSupport.seller(order.shipments().getFirst().sellerOrgId()));
        problem(transitionRaw(shipment, "confirmed"), 403, "orders.transition-forbidden");

        TestSupport.as(TestSupport.fulfilmentOps());
        for (String step : new String[] {"confirmed", "packed", "ready", "picked_up"}) {
            assertThat(transition(shipment, step, null, null).path("outcome").asString()).isEqualTo("applied");
        }
        assertThat(followUps.runDue()).isGreaterThanOrEqualTo(1);

        long total = order.shipments().getFirst().totalMinor();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/capture"))
                .withHeader("Idempotency-Key", WireMock.equalTo("capture-" + shipment))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo(String.valueOf(total))))
                .withRequestBody(WireMock.matchingJsonPath("$.finalCapture", WireMock.equalTo("true"))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + order.id()
                + "/commit")).withHeader("Idempotency-Key", WireMock.equalTo(order.id())));
        assertThat(jdbc.sql("select capture_id is not null from shipment where id = :id")
                .param("id", UUID.fromString(shipment)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void customersCancelOneShipmentUntilItIsPacked() {
        String customer = id("cust");
        String org = id("org");
        Order order = placedOrder(customer, UUID.randomUUID().toString(),
                spec(org, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER),
                spec(id("org-2"), FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        String first = order.shipments().get(0).id();
        String second = order.shipments().get(1).id();

        TestSupport.as(TestSupport.customer(customer));
        JsonNode view = ok(send("POST", "/v1/orders/" + order.number() + "/shipments/" + first + "/cancel",
                "{\"reason\":\"Changed my mind\"}"));
        assertThat(view.path("orderNumber").asString()).isEqualTo(order.number());
        assertThat(view.path("shipments").get(0).path("status").asString()).isEqualTo("cancelled");
        assertThat(view.path("shipments").get(1).path("status").asString()).isEqualTo("placed");
        assertThat(view.path("status").asString()).isEqualTo("placed");

        followUps.runDue();
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + order.paymentId()
                + "/cancel")));

        TestSupport.as(TestSupport.seller(order.shipments().get(1).sellerOrgId()));
        transition(second, "confirmed", null, null);
        transition(second, "packed", null, null);
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/orders/" + order.number() + "/shipments/" + second + "/cancel", "{}"), 409,
                "orders.cancel-window-closed");

        TestSupport.as(TestSupport.customer(id("someone-else")));
        problem(send("POST", "/v1/orders/" + order.number() + "/shipments/" + second + "/cancel", "{}"), 404,
                "orders.order-not-found");
    }

    @Test
    void transitionsAreIdempotentPerEventId() {
        String org = id("org");
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(org, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        String shipment = order.shipments().getFirst().id();
        TestSupport.as(TestSupport.seller(org));
        assertThat(transition(shipment, "confirmed", null, "k-1").path("outcome").asString()).isEqualTo("applied");
        assertThat(transition(shipment, "packed", null, "k-2").path("outcome").asString()).isEqualTo("applied");
        long rows = history(shipment);

        assertThat(transition(shipment, "confirmed", null, "k-1").path("outcome").asString()).isEqualTo("replayed");
        assertThat(transition(shipment, "packed", null, "k-3").path("outcome").asString()).isEqualTo("unchanged");
        assertThat(history(shipment)).isEqualTo(rows);
        assertThat(status(shipment)).isEqualTo("PACKED");
        problem(transitionRaw(shipment, "confirmed"), 409, "orders.invalid-transition");
    }

    @Test
    void sellerQueueAndStaffSearch() {
        String org = id("org");
        Order order = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(org, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        TestSupport.as(TestSupport.seller(org));
        JsonNode queue = ok(send("GET", "/v1/seller/shipments?status=placed", null));
        assertThat(queue.path("total").asLong()).isEqualTo(1);
        assertThat(queue.path("items").get(0).path("orderNumber").asString()).isEqualTo(order.number());
        assertThat(queue.path("items").get(0).path("shipment").path("nextActions").get(0).asString())
                .isEqualTo("confirmed");
        problem(send("GET", "/v1/admin/orders", null), 403, "orders.staff-only");

        TestSupport.as(TestSupport.admin());
        JsonNode found = ok(send("GET", "/v1/admin/orders?seller=" + org + "&shipmentStatus=placed", null));
        assertThat(found.path("items")).hasSize(1);
        assertThat(found.path("items").get(0).path("paymentId").asString()).isEqualTo(order.paymentId());
        problem(send("GET", "/v1/seller/shipments", null), 403, "orders.org-required");
    }
}
