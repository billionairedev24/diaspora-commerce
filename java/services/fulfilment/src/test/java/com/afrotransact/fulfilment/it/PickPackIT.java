package com.afrotransact.fulfilment.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** orders.order.placed → FC tasks and dispatch pool; pick, pack and stage move the shipment in orders-service. */
class PickPackIT extends FulfilmentIntegrationTest {

    @Test
    void orderPlacedCreatesTasksAndPoolThenPickPackReadyMovesOrders() {
        String orderId = UUID.randomUUID().toString();
        String fbaDelivery = UUID.randomUUID().toString();
        String retailCarrier = UUID.randomUUID().toString();
        String sellerDelivery = UUID.randomUUID().toString();
        String sellerCarrier = UUID.randomUUID().toString();
        UPSTREAMS.reservation(orderId, NODE, "ATVJOLLOF01", "ATVRICE0001");
        UUID eventId = UUID.randomUUID();
        var order = order(orderId, "10001",
                shipment(fbaDelivery, "FBA_AFROTRANSACT", "AFROTRANSACT_DELIVERY", true, false, "ATVJOLLOF01"),
                shipment(retailCarrier, "RETAIL_1P", "CARRIER", false, false, "ATVRICE0001"),
                shipment(sellerDelivery, "SELLER_FULFILLED", "AFROTRANSACT_DELIVERY", true, true, "ATVTRAY0001"),
                shipment(sellerCarrier, "SELLER_FULFILLED", "CARRIER", false, false, "ATVSOAP0001"));

        assertThat(intake.orderPlaced(eventId, order)).as("2 FC tasks + 2 delivery shipments").isEqualTo(4);
        assertThat(intake.orderPlaced(eventId, order)).as("redelivery is a no-op").isZero();

        TestSupport.as(TestSupport.fulfilmentOps());
        JsonNode queue = ok(send("GET", "/v1/fc/tasks?nodeId=" + NODE + "&status=PICK_PENDING&limit=200", null));
        JsonNode carrierTask = find(queue, retailCarrier);
        JsonNode deliveryTask = find(queue, fbaDelivery);
        assertThat(carrierTask.path("nodeId").asString()).isEqualTo(NODE);
        assertThat(deliveryTask.path("packageLabel").asString()).matches("ATS[0-9A-Z]{9}");
        String taskId = carrierTask.path("id").asString();

        ok(send("POST", "/v1/fc/tasks/" + taskId + "/pick/start", null));
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + retailCarrier
                        + "/transitions"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withHeader("Idempotency-Key", WireMock.equalTo("fulfilment:" + taskId + ":confirmed"))
                .withRequestBody(WireMock.containing("\"to\":\"confirmed\"")));

        problem(send("POST", "/v1/fc/tasks/" + taskId + "/pick", Map.of("picked", Map.of("ATVRICE0001", 0))), 422,
                "fulfilment.short-pick-reason-required");
        JsonNode shortPick = ok(send("POST", "/v1/fc/tasks/" + taskId + "/pick",
                Map.of("picked", Map.of("ATVRICE0001", 0), "shortReason", "Bin A-12 empty")));
        assertThat(shortPick.path("status").asString()).isEqualTo("PICK_EXCEPTION");
        ok(send("POST", "/v1/fc/tasks/" + taskId + "/pick/start", null));
        JsonNode picked = ok(send("POST", "/v1/fc/tasks/" + taskId + "/pick",
                Map.of("picked", Map.of("ATVRICE0001", 1), "lots", Map.of("ATVRICE0001", "LOT-7"))));
        assertThat(picked.path("status").asString()).isEqualTo("PICKED");
        assertThat(picked.path("lines").get(0).path("lotIds").asString()).isEqualTo("LOT-7");

        problem(send("POST", "/v1/fc/tasks/" + taskId + "/pack", Map.of("weightLb", 10.5)), 422,
                "fulfilment.carrier-label-required");
        problem(send("POST", "/v1/fc/tasks/" + taskId + "/ready", null), 409, "fulfilment.task-invalid-transition");
        ok(send("POST", "/v1/fc/tasks/" + taskId + "/pack",
                Map.of("weightLb", 10.5, "carrier", "usps", "trackingNumber", "9400111899223")));
        ok(send("POST", "/v1/fc/tasks/" + taskId + "/ready", null));
        for (String to : List.of("packed", "ready")) {
            WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + retailCarrier
                    + "/transitions")).withRequestBody(WireMock.containing("\"to\":\"" + to + "\"")));
        }
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/shipments/" + retailCarrier
                + "/tracking")).withRequestBody(WireMock.containing("9400111899223")));
        assertThat(outboxTypes(retailCarrier)).containsExactly("fulfilment.task.created",
                "fulfilment.task.pick_exception", "fulfilment.task.picked", "fulfilment.task.packed",
                "fulfilment.task.ready");

        JsonNode pool = ok(send("GET", "/v1/dispatch/shipments?nodeId=" + NODE + "&limit=500", null));
        JsonNode fromFc = find(pool, fbaDelivery);
        JsonNode fromSeller = find(pool, sellerDelivery);
        assertThat(fromFc.path("origin").asString()).isEqualTo("FC");
        assertThat(fromFc.path("label").asString()).as("one label from pack to doorstep")
                .isEqualTo(deliveryTask.path("packageLabel").asString());
        assertThat(fromSeller.path("origin").asString()).isEqualTo("SELLER");
        assertThat(fromSeller.path("perishable").asBoolean()).isTrue();

        assertThat(intake.shipmentCancelled(UUID.randomUUID(), sellerCarrier, "Customer cancelled")).isFalse();
        assertThat(intake.shipmentCancelled(UUID.randomUUID(), fbaDelivery, "Customer cancelled")).isTrue();
        assertThat(ok(send("GET", "/v1/fc/tasks/" + deliveryTask.path("id").asString(), null)).path("status")
                .asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("select status from delivery_shipment where shipment_id = :id").param("id", fbaDelivery)
                .query(String.class).single()).isEqualTo("CANCELLED");
        jdbc.sql("update delivery_shipment set status = 'CANCELLED' where shipment_id = :id")
                .param("id", sellerDelivery).update();
    }

    @Test
    void tasksNeedFulfilmentOperate() {
        TestSupport.as(TestSupport.dispatcher());
        problem(send("GET", "/v1/fc/tasks", null), 403, "auth.missing-permission");
    }

    private static JsonNode find(JsonNode array, String shipmentId) {
        for (JsonNode n : array) {
            if (shipmentId.equals(n.path("shipmentId").asString())) {
                return n;
            }
        }
        throw new AssertionError("No entry for shipment " + shipmentId + " in " + array);
    }
}
