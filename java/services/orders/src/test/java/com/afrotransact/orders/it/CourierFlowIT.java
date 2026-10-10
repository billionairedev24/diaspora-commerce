package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** AfroTransact Delivery: driver-app events with proof, handover codes, capture + commit, live feed. */
class CourierFlowIT extends OrdersIntegrationTest {

    private FollowUpService followUps;

    @BeforeEach
    void wire(@Autowired FollowUpService service) {
        this.followUps = service;
    }

    private String event(String eventId, String status, String proof) {
        return "{\"eventId\":\"" + eventId + "\",\"status\":\"" + status + "\",\"occurredAt\":\"2026-10-01T15:00:"
                + String.format("%02d", Math.abs(eventId.hashCode()) % 60) + "Z\"" + (proof == null ? ""
                : ",\"proof\":" + proof) + "}";
    }

    @Test
    void driverEventsMoveAPerishableDeliveryCheckTheCodeAndCaptureOnPickup() throws Exception {
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        String customer = id("cust");
        Order order = placedOrder(customer, paymentId, new Spec(id("org-fba"), FulfilmentType.RETAIL_1P,
                DeliveryMethod.AFROTRANSACT_DELIVERY, true, DeliveryPreferences.defaults(), 2200, 1));
        String shipment = order.shipments().getFirst().id();

        TestSupport.as(TestSupport.customer(customer));
        String code = ok(send("GET", "/v1/orders/" + order.number(), null)).path("shipments").get(0)
                .path("handoverCode").asString();
        assertThat(code).isEqualTo("4821");

        TestSupport.as(TestSupport.fulfilmentOps());
        transition(shipment, "confirmed", null, null);
        transition(shipment, "packed", null, null);
        transition(shipment, "ready", null, null);

        TestSupport.as(TestSupport.driver());
        JsonNode brief = ok(send("GET", "/v1/shipments/" + shipment + "/delivery-brief", null));
        assertThat(brief.path("perishable").asBoolean()).isTrue();
        assertThat(brief.path("handoverRequired").asBoolean()).isTrue();
        assertThat(brief.path("safePlaceAllowed").asBoolean()).isFalse();
        assertThat(brief.path("shipTo").path("instructions").asString()).isEqualTo("Gate 4");
        assertThat(brief.toString()).doesNotContain("4821");

        // Staff follow the live feed (SSE).
        TestSupport.as(TestSupport.admin());
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        CompletableFuture<HttpResponse<Stream<String>>> stream = http.sendAsync(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/shipments/stream")).header("Accept", "text/event-stream")
                .build(), HttpResponse.BodyHandlers.ofLines());
        HttpResponse<Stream<String>> feed = stream.get(10, TimeUnit.SECONDS);
        CompletableFuture<String> pickedUpLine = CompletableFuture.supplyAsync(() -> feed.body()
                .filter(l -> l.startsWith("data:") && l.contains(shipment) && l.contains("picked_up"))
                .findFirst().orElse(""));

        TestSupport.as(TestSupport.driver());
        JsonNode picked = ok(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                event("drv-1", "picked_up", null)));
        assertThat(picked.path("outcome").asString()).isEqualTo("applied");
        assertThat(picked.path("order").isNull()).isTrue();
        assertThat(pickedUpLine.get(5, TimeUnit.SECONDS)).contains("COURIER");

        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/capture"))
                .withHeader("Idempotency-Key", WireMock.equalTo("capture-" + shipment)));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + order.id()
                + "/groups/commit")).withHeader("Idempotency-Key", WireMock.equalTo("commit-" + shipment)));

        ok(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("drv-2", "out_for_delivery", null)));
        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("drv-3", "delivered",
                "{\"photoAssetId\":\"img-1\",\"handoverMethod\":\"safe_place\",\"safePlace\":\"Porch\"}")), 422,
                "orders.safe-place-not-allowed");
        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("drv-4", "delivered",
                "{\"photoAssetId\":\"img-1\",\"handoverMethod\":\"customer\",\"handoverCode\":\"1111\"}")), 422,
                "orders.handover-code-invalid");
        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("drv-5", "delivered",
                "{\"handoverMethod\":\"customer\",\"handoverCode\":\"" + code + "\"}")), 422,
                "orders.proof-photo-required");
        assertThat(status(shipment)).isEqualTo("OUT_FOR_DELIVERY");

        String delivered = event("drv-6", "delivered", "{\"photoAssetId\":\"img-9\",\"handoverMethod\":\"customer\","
                + "\"handoverCode\":\"" + code + "\",\"latitude\":29.76,\"longitude\":-95.37}");
        assertThat(ok(send("POST", "/v1/shipments/" + shipment + "/courier-events", delivered))
                .path("outcome").asString()).isEqualTo("applied");
        assertThat(ok(send("POST", "/v1/shipments/" + shipment + "/courier-events", delivered))
                .path("outcome").asString()).isEqualTo("replayed");
        assertThat(status(shipment)).isEqualTo("DELIVERED");
        String storedProof = jdbc.sql("select proof::text from shipment_status_change where shipment_id = :s and "
                + "to_status = 'DELIVERED'").param("s", UUID.fromString(shipment)).query(String.class).single();
        assertThat(storedProof).contains("img-9").doesNotContain(code);

        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/settlements/" + order.id()
                + "/delivered")));

        TestSupport.as(TestSupport.customer(customer));
        JsonNode after = ok(send("GET", "/v1/orders/" + order.number(), null));
        assertThat(after.path("shipments").get(0).path("handoverCode").isNull()).isTrue();
        assertThat(after.path("shipments").get(0).path("captured").asBoolean()).isTrue();
    }

    @Test
    void onlyDriversSendCourierEventsAndOnlyForAfroTransactDelivery() {
        Order carrier = placedOrder(id("cust"), UUID.randomUUID().toString(),
                spec(id("org"), FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER));
        String shipment = carrier.shipments().getFirst().id();
        TestSupport.as(TestSupport.fulfilmentOps());
        transition(shipment, "confirmed", null, null);
        transition(shipment, "packed", null, null);
        transition(shipment, "ready", null, null);

        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("x-1", "picked_up", null)), 403,
                "auth.missing-permission");
        TestSupport.as(TestSupport.driver());
        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events", event("x-2", "picked_up", null)), 403,
                "orders.transition-forbidden");
        problem(transitionRaw(shipment, "picked_up"), 403, "orders.use-courier-events");
    }
}
