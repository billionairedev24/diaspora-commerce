package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S255: an AFROTRANSACT_DELIVERY offer is collected at the seller's pickup location. Checkout measures the road
 * distance (geo), prices it (payments quote), puts the customer charge on the order and snapshots the seller fee and
 * distance on the shipment; beyond the radius the offer is not deliverable to that address.
 */
class AfroTransactDeliveryCheckoutIT extends OrdersIntegrationTest {

    private static String line(String atin, String org, String offerId, long price, int qty) {
        return "{\"variantAtin\":\"" + atin + "\",\"productAtin\":\"P" + atin + "\",\"title\":\"Item " + atin
                + "\",\"sellerOrgId\":\"" + org + "\",\"sellerKind\":\"SELLER\",\"fulfilment\":"
                + "\"AFROTRANSACT_DELIVERY\",\"priceMinor\":" + price + ",\"currency\":\"USD\",\"quantity\":" + qty
                + ",\"unavailable\":false,\"offerId\":\"" + offerId + "\"}";
    }

    private void store(String offerId, String org, String locationId, boolean enabled) {
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/public/offers/" + offerId)), 200,
                "{\"offerId\":\"" + offerId + "\",\"fulfilment\":\"AFROTRANSACT_DELIVERY\",\"shipFromLocationId\":\""
                        + locationId + "\"}");
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/ship-from-locations/" + locationId))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN)), 200,
                "{\"id\":\"" + locationId + "\",\"orgId\":\"" + org + "\",\"position\":{\"lat\":29.7589,"
                        + "\"lng\":-95.3677},\"afrotransactPickup\":{\"enabled\":" + enabled + "}}");
    }

    private void distance(double roadMiles) {
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/distance"))
                .withRequestBody(WireMock.matchingJsonPath("$.from.lat", WireMock.equalTo("29.7589"))), 200,
                "{\"straightLineMiles\":5.9,\"roadMiles\":" + roadMiles + ",\"durationMinutes\":18.5,"
                        + "\"method\":\"ROAD_OSRM\",\"cached\":false}");
    }

    private void quote(String org, boolean available, long charge, long fee) {
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/delivery-pricing/quote"))
                .withRequestBody(WireMock.matchingJsonPath("$.sellerOrgId", WireMock.equalTo(org))), 200,
                "{\"available\":" + available + ",\"unavailableReason\":" + (available ? "null"
                        : "\"BEYOND_MAX_RADIUS\"") + ",\"customerChargeMinor\":" + charge + ",\"sellerFeeMinor\":"
                        + fee + ",\"scheduleVersion\":1}");
    }

    @Test
    void theQuotedChargeGoesOnTheOrderAndTheSellerFeeAndDistanceOnTheShipment() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org-store");
        String offerA = UUID.randomUUID().toString();
        String offerB = UUID.randomUUID().toString();
        String location = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Ama's Market", "AFROTRANSACT_DELIVERY",
                line(id("ATD"), org, offerA, 1500, 2), line(id("ATD"), org, offerB, 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        store(offerA, org, location, true);
        store(offerB, org, location, true);
        distance(7.4);
        quote(org, true, 1128, 201);
        UPSTREAMS.paymentIntent(customer, paymentId);
        TestSupport.as(TestSupport.customer(customer));

        JsonNode created = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer), 201);

        // items 3000 + 1000, tax 8.25 % (248 + 83), delivery charge 1128.
        assertThat(created.path("totalMinor").asLong()).isEqualTo(4000 + 248 + 83 + 1128);
        assertThat(created.path("shipments")).hasSize(1);
        assertThat(created.path("shipments").get(0).path("deliveryMethod").asString())
                .isEqualTo("AFROTRANSACT_DELIVERY");
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/delivery-pricing/quote"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.distanceMiles", WireMock.equalTo("7.4"))));
        // The address has no stored position: geo geocodes the street address.
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/distance"))
                .withRequestBody(WireMock.matchingJsonPath("$.to.address.postalCode", WireMock.equalTo("77002"))));
        // Payment lines: AFROTRANSACT_DELIVERY, shipping on the first line, the 201 fee spread by items 3000 / 1000.
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].fulfilment",
                        WireMock.equalTo("AFROTRANSACT_DELIVERY")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor", WireMock.equalTo("1128")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].deliveryFeeMinor", WireMock.equalTo("151")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[1].deliveryFeeMinor", WireMock.equalTo("50"))));

        String orderId = created.path("orderId").asString();
        JsonNode snapshot = JSON.readTree(jdbc.sql("select local_delivery::text from shipment where order_id = :o")
                .param("o", UUID.fromString(orderId)).query(String.class).single());
        assertThat(snapshot.path("shipFromLocationId").asString()).isEqualTo(location);
        assertThat(snapshot.path("distanceMiles").asDouble()).isEqualTo(7.4);
        assertThat(snapshot.path("durationMinutes").asDouble()).isEqualTo(18.5);
        assertThat(snapshot.path("distanceMethod").asString()).isEqualTo("ROAD_OSRM");
        assertThat(snapshot.path("sellerFeeMinor").asLong()).isEqualTo(201);
        assertThat(snapshot.path("customerChargeMinor").asLong()).isEqualTo(1128);
        assertThat(jdbc.sql("select fulfilment_type from shipment where order_id = :o")
                .param("o", UUID.fromString(orderId)).query(String.class).single()).isEqualTo("SELLER_FULFILLED");

        JsonNode mine = ok(send("GET", "/v1/orders/" + created.path("orderNumber").asString(), null));
        JsonNode ld = mine.path("shipments").get(0).path("localDelivery");
        assertThat(ld.path("roadMiles").asDouble()).isEqualTo(7.4);
        assertThat(ld.path("sellerFeeMinor").isNull()).as("hidden from the customer").isTrue();
    }

    @Test
    void beyondTheRadiusTheOfferIsNotDeliverableToThatAddress() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org-far");
        String offer = UUID.randomUUID().toString();
        String location = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Far Store", "AFROTRANSACT_DELIVERY",
                line(id("ATD"), org, offer, 1500, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        store(offer, org, location, true);
        distance(31.2);
        quote(org, false, 0, 0);
        TestSupport.as(TestSupport.customer(customer));

        JsonNode p = problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer), 422, "orders.delivery-out-of-range");
        assertThat(p.path("detail").asString()).contains("31 miles");
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.containing(org)));
    }

    @Test
    void aDisabledPickupLocationOrACarrierChoiceIsRefused() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org-off");
        String offer = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Closed Store", "AFROTRANSACT_DELIVERY",
                line(id("ATD"), org, offer, 1500, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        store(offer, org, UUID.randomUUID().toString(), false);
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key", "k-" + customer,
                "Authorization", "Bearer " + bearer), 422, "orders.delivery-pickup-unavailable");

        String other = id("cust");
        String otherBearer = "tok-" + other;
        String location = UUID.randomUUID().toString();
        String offer2 = UUID.randomUUID().toString();
        UPSTREAMS.cart(otherBearer, Upstreams.pkg(org, "Open Store", "AFROTRANSACT_DELIVERY",
                line(id("ATD"), org, offer2, 1500, 1)));
        UPSTREAMS.addresses(otherBearer, "addr-1", "TX");
        store(offer2, org, location, true);
        TestSupport.as(TestSupport.customer(other));
        problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\",\"deliverySelections\":[{\"packageId\":\""
                + org + ":AFROTRANSACT_DELIVERY\",\"option\":\"carrier\"}]}", "Idempotency-Key", "k-" + other,
                "Authorization", "Bearer " + otherBearer), 422, "orders.invalid-delivery-selection");
    }
}
