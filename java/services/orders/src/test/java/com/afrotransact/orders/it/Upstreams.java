package com.afrotransact.orders.it;

import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

/** WireMock stand-ins for identity, cart, inventory, payments, promise, content and catalog. */
final class Upstreams {

    static final String SERVICE_TOKEN = "svc-orders-token";

    private final WireMockServer server;

    Upstreams(WireMockServer server) {
        this.server = server;
    }

    void json(MappingBuilder mapping, int status, String body) {
        server.stubFor(mapping.willReturn(WireMock.aResponse().withStatus(status)
                .withHeader("Content-Type", status >= 400 ? "application/problem+json" : "application/json")
                .withBody(body)));
    }

    /** identity /oauth2/token (client credentials) and an unreachable promise service. */
    void common() {
        json(WireMock.post(WireMock.urlPathEqualTo("/oauth2/token"))
                .withBasicAuth("orders-service", "test-orders-secret")
                .withRequestBody(WireMock.containing("grant_type=client_credentials")), 200,
                "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/promises")), 503, "{\"code\":\"promise.down\"}");
        json(WireMock.get(WireMock.urlPathMatching("/v1/public/variants/.*")), 404, "{\"code\":\"catalog.not-found\"}");
        json(WireMock.post(WireMock.urlPathMatching("/v1/carts/.*/ordered")), 204, "");
        json(WireMock.post(WireMock.urlPathMatching("/v1/reservations/.*/(confirm|commit|release)")), 200,
                "{\"status\":\"OK\"}");
        json(WireMock.post(WireMock.urlPathMatching("/v1/settlements/.*/delivered")), 200,
                "{\"status\":\"HELD\"}");
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/markets/eligibility")), 200,
                "{\"country\":\"US\",\"deliveryAllowed\":true}");
        // S272: nothing is a pre-order unless a test says so.
        server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility")).atPriority(100)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[]}")));
        // S261: no delivery run is open unless a test says so.
        server.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/public/runs/available")).atPriority(100)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"runs\":[]}")));
        // ADR 0005 §3: no staff carrier rule unless a test says so (checkouts keep the flat carrier rates).
        server.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/service/delivery-fees/carrier")).atPriority(100)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"applies\":false}")));
    }

    /** A cart for one customer token. Lines: {atin, sellerOrg, fulfilment AFROTRANSACT|SELLER, price, qty}. */
    void cart(String bearer, String... packagesJson) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/cart"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + bearer)), 200,
                "{\"packages\":[" + String.join(",", packagesJson) + "],\"currency\":\"USD\",\"checkoutable\":true,"
                        + "\"version\":3}");
    }

    static String pkg(String sellerOrg, String sellerName, String fulfilment, String... lines) {
        return "{\"sellerOrgId\":\"" + sellerOrg + "\",\"sellerName\":\"" + sellerName + "\",\"fulfilment\":\""
                + fulfilment + "\",\"lines\":[" + String.join(",", lines) + "]}";
    }

    static String line(String atin, String sellerOrg, String fulfilment, long price, int qty) {
        return "{\"variantAtin\":\"" + atin + "\",\"productAtin\":\"P" + atin + "\",\"title\":\"Item " + atin
                + "\",\"sellerOrgId\":\"" + sellerOrg + "\",\"sellerKind\":\"SELLER\",\"fulfilment\":\"" + fulfilment
                + "\",\"priceMinor\":" + price + ",\"currency\":\"USD\",\"quantity\":" + qty
                + ",\"unavailable\":false}";
    }

    void addresses(String bearer, String addressId, String state) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/me/addresses"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + bearer)), 200,
                "[{\"id\":\"" + addressId + "\",\"fullName\":\"Ada Obi\",\"phone\":\"+17135550142\",\"line1\":\"1 Main"
                        + " St\",\"city\":\"Houston\",\"state\":\"" + state + "\",\"postalCode\":\"77002\","
                        + "\"country\":\"US\",\"isDefault\":true}]");
    }

    void perishable(String atin) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/public/variants/" + atin)), 200,
                "{\"product\":{\"atin\":\"P" + atin + "\",\"productType\":\"perishable\",\"attributes\":{}},"
                        + "\"variant\":{\"atin\":\"" + atin + "\"}}");
    }

    void reservation(String sku, String nodeId, int qty) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/reservations"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].sku", WireMock.equalTo(sku))), 201,
                "{\"status\":\"HELD\",\"lines\":[{\"lineNo\":1,\"sku\":\"" + sku + "\",\"nodeId\":\"" + nodeId
                        + "\",\"qty\":" + qty + "}]}");
    }

    void insufficient(String sku) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/reservations"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].sku", WireMock.equalTo(sku))), 409,
                "{\"code\":\"inventory.insufficient-stock\",\"detail\":\"short\",\"shortfalls\":[{\"sku\":\"" + sku
                        + "\",\"requested\":2,\"available\":1,\"missing\":1}]}");
    }

    void paymentIntent(String customerId, String paymentId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customerId))),
                200, "{\"id\":\"" + paymentId + "\",\"status\":\"REQUIRES_PAYMENT_METHOD\",\"clientSecret\":\""
                        + "pi_" + paymentId + "_secret\",\"amountMinor\":1}");
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/cancel")), 200,
                "{\"id\":\"" + paymentId + "\",\"status\":\"CANCELED\"}");
    }

    /** {@code GET /v1/payments/{id}} (with or without refresh) answers this status. */
    void paymentStatus(String paymentId, String status) {
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/payments/" + paymentId)), 200,
                "{\"id\":\"" + paymentId + "\",\"status\":\"" + status + "\",\"amountMinor\":1}");
    }

    void capture(String paymentId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/capture")), 200,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"paymentId\":\"" + paymentId
                        + "\",\"amountMinor\":1,\"paymentStatus\":\"PARTIALLY_CAPTURED\"}");
    }
}
