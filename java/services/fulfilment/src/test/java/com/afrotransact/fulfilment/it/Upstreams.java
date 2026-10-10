package com.afrotransact.fulfilment.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

/** WireMock stand-ins for identity (token), orders, inventory and geo. */
final class Upstreams {

    static final String SERVICE_TOKEN = "svc-fulfilment-token";
    static final String WRONG_CODE = "0000";

    private final WireMockServer server;

    Upstreams(WireMockServer server) {
        this.server = server;
    }

    void json(MappingBuilder mapping, int status, String body) {
        server.stubFor(mapping.willReturn(WireMock.aResponse().withStatus(status)
                .withHeader("Content-Type", status >= 400 ? "application/problem+json" : "application/json")
                .withBody(body)));
    }

    /** Token, orders accepting everything except a wrong handover code, geo zones for Houston ZIPs. */
    void common() {
        json(WireMock.post(WireMock.urlPathEqualTo("/oauth2/token"))
                .withBasicAuth("fulfilment-service", "test-fulfilment-secret")
                .withRequestBody(WireMock.containing("grant_type=client_credentials")), 200,
                "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        json(WireMock.post(WireMock.urlPathMatching("/v1/shipments/.*/transitions")), 200,
                "{\"result\":\"applied\"}");
        json(WireMock.post(WireMock.urlPathMatching("/v1/shipments/.*/tracking")), 200, "{}");
        json(WireMock.post(WireMock.urlPathMatching("/v1/shipments/.*/courier-events")).atPriority(10), 200,
                "{\"result\":\"applied\"}");
        json(WireMock.post(WireMock.urlPathMatching("/v1/shipments/.*/courier-events")).atPriority(1)
                .withRequestBody(WireMock.containing("\"handoverCode\":\"" + WRONG_CODE + "\"")), 422,
                "{\"code\":\"orders.handover-code-invalid\",\"detail\":\"The handover code does not match; ask the "
                        + "customer for the 4-digit code\"}");
        json(WireMock.get(WireMock.urlPathMatching("/v1/zip/770(42|57|81)")), 200,
                "{\"line1\":\"\",\"city\":\"Houston\",\"state\":\"TX\",\"postalCode\":\"77042\",\"lat\":29.73,"
                        + "\"lon\":-95.51}");
        json(WireMock.get(WireMock.urlPathMatching("/v1/zip/.*")).atPriority(10), 404,
                "{\"code\":\"geo.not-found\"}");
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/zones/covering")), 200,
                "{\"zones\":[{\"name\":\"Houston SW\",\"type\":\"DELIVERY\",\"ownerId\":\"HOU-FC1\",\"active\":true}]}");
    }

    /** Inventory reservation of an order: every SKU at the node. */
    void reservation(String orderId, String nodeId, String... skus) {
        StringBuilder lines = new StringBuilder();
        int n = 1;
        for (String sku : skus) {
            if (lines.length() > 0) {
                lines.append(',');
            }
            lines.append("{\"lineNo\":").append(n++).append(",\"sku\":\"").append(sku).append("\",\"nodeId\":\"")
                    .append(nodeId).append("\",\"qty\":1,\"allocations\":[{\"lotId\":\"LOT-").append(sku)
                    .append("\"}]}");
        }
        json(WireMock.get(WireMock.urlPathEqualTo("/v1/reservations/" + orderId)), 200,
                "{\"orderId\":\"" + orderId + "\",\"status\":\"CONFIRMED\",\"lines\":[" + lines + "]}");
    }

    /** orders unreachable for courier events of one shipment. */
    void ordersDownFor(String shipmentId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/shipments/" + shipmentId + "/courier-events"))
                .atPriority(0), 503, "{\"code\":\"orders.unavailable\"}");
    }

    void ordersUpFor(String shipmentId) {
        json(WireMock.post(WireMock.urlPathEqualTo("/v1/shipments/" + shipmentId + "/courier-events"))
                .atPriority(0), 200, "{\"result\":\"applied\"}");
    }
}
