package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.orders.application.CarrierEventService;
import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** carrier.tracking.updated events move a seller-fulfilled shipment, trigger the capture and the settlement hold. */
class CarrierFlowIT extends OrdersIntegrationTest {

    private CarrierEventService carriers;
    private FollowUpService followUps;

    @BeforeEach
    void wire(@Autowired CarrierEventService carrierEvents, @Autowired FollowUpService followUpService) {
        this.carriers = carrierEvents;
        this.followUps = followUpService;
    }

    private CarrierEventService.Result scan(String tracking, String reference, String state, String eventId,
                                            Instant at) {
        return carriers.apply(UUID.randomUUID(), new CarrierEventService.Scan("ups", tracking, reference, state,
                eventId, at, "UPS scan " + state, "Houston, TX"));
    }

    @Test
    void carrierScansMoveTheShipmentForwardCaptureOnPickupAndStartSettlementOnDelivery() {
        String org = id("org");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        Order order = placedOrder(id("cust"), paymentId,
                spec(org, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        String shipment = order.shipments().getFirst().id();
        String tracking = "1Z" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);

        TestSupport.as(TestSupport.seller(org));
        transition(shipment, "confirmed", null, null);
        transition(shipment, "packed", null, null);
        transition(shipment, "ready", null, null);
        JsonNode labelled = ok(send("POST", "/v1/shipments/" + shipment + "/tracking",
                "{\"carrier\":\"UPS\",\"trackingNumber\":\"" + tracking + "\"}"));
        assertThat(labelled.path("shipments").get(0).path("trackingNumber").asString()).isEqualTo(tracking);

        Instant t0 = Instant.parse("2026-10-01T14:00:00Z");
        assertThat(scan(tracking, null, "label_created", "e0", t0)).isEqualTo(CarrierEventService.Result.IGNORED);
        assertThat(scan(tracking, null, "picked_up", "e1", t0.plusSeconds(60)))
                .isEqualTo(CarrierEventService.Result.APPLIED);
        assertThat(scan(tracking, null, "in_transit", "e2", t0.plusSeconds(3600)))
                .isEqualTo(CarrierEventService.Result.APPLIED);
        assertThat(status(shipment)).isEqualTo("IN_TRANSIT");

        // A late picked_up scan and a replayed carrier event never move it back.
        assertThat(scan(tracking, null, "picked_up", "e1b", t0.plusSeconds(7200)))
                .isEqualTo(CarrierEventService.Result.IGNORED);
        assertThat(scan(tracking, null, "in_transit", "e2", t0.plusSeconds(3600)))
                .isEqualTo(CarrierEventService.Result.DUPLICATE);
        assertThat(status(shipment)).isEqualTo("IN_TRANSIT");

        followUps.runDue();
        long total = order.shipments().getFirst().totalMinor();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/capture"))
                .withHeader("Idempotency-Key", WireMock.equalTo("capture-" + shipment))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.shipmentId", WireMock.equalTo(shipment)))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo(String.valueOf(total))))
                .withRequestBody(WireMock.matchingJsonPath("$.finalCapture", WireMock.equalTo("true"))));
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/reservations/.*/commit"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order.id())));

        // Matched by label reference (our shipment id) as well as by tracking number.
        assertThat(scan(null, shipment, "out_for_delivery", "e3", t0.plusSeconds(86_400)))
                .isEqualTo(CarrierEventService.Result.APPLIED);
        assertThat(scan(tracking, null, "delivered", "e4", t0.plusSeconds(90_000)))
                .isEqualTo(CarrierEventService.Result.APPLIED);
        assertThat(status(shipment)).isEqualTo("DELIVERED");
        assertThat(outbox("orders.shipment.delivered", order.id())).isEqualTo(1);
        assertThat(outbox("orders.shipment.status_changed", order.id())).isEqualTo(1 + 3 + 4);

        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/settlements/" + order.id()
                + "/delivered")).withRequestBody(WireMock.matchingJsonPath("$.deliveredAt",
                WireMock.equalTo(t0.plusSeconds(90_000).toString()))));

        TestSupport.as(TestSupport.admin());
        JsonNode view = ok(send("GET", "/v1/orders/" + order.number(), null));
        assertThat(view.path("status").asString()).isEqualTo("completed");
        JsonNode timeline = view.path("shipments").get(0).path("timeline");
        assertThat(timeline).extracting(t -> t.path("to").asString())
                .containsExactly("placed", "confirmed", "packed", "ready", "picked_up", "in_transit",
                        "out_for_delivery", "delivered");
        assertThat(timeline.get(4).path("actorType").asString()).isEqualTo("CARRIER");
        assertThat(timeline.get(4).path("source").asString()).isEqualTo("carrier");
        assertThat(timeline.get(1).path("actorType").asString()).isEqualTo("SELLER");
    }

    @Test
    void unknownParcelsAreIgnored() {
        assertThat(scan("1ZUNKNOWN", null, "delivered", "x", Instant.now()))
                .isEqualTo(CarrierEventService.Result.UNMATCHED);
    }
}
