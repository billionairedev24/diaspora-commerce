package com.afrotransact.retail.it;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

/** WireMock standing in for identity (token), catalog (public variants) and inventory (receipts, nodes). */
final class Stubs {

    static final String TOKEN = "svc-token-retail";
    static final String SELLER_ATIN = "AT0SELLER1";

    private Stubs() {
    }

    /** A fresh ATIN catalog knows as a live AfroTransact Retail (PLATFORM) variant. */
    static String platformAtin() {
        return "ATP" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
    }

    static void install(WireMockServer wm) {
        wm.stubFor(WireMock.post(WireMock.urlPathEqualTo("/oauth2/token"))
                .withBasicAuth("retail-service", "it-secret")
                .withRequestBody(WireMock.containing("grant_type=client_credentials"))
                .willReturn(json(200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":300}")));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/public/variants/ATP[0-9A-Z]{7}"))
                .willReturn(json(200, variant(null, "PLATFORM", "AfroTransact Brand Jollof Seasoning, 6 oz"))));
        wm.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/public/variants/" + SELLER_ATIN))
                .willReturn(json(200, variant(SELLER_ATIN, "SELLER", "Marketplace Jollof"))));
        wm.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/public/variants/.*")).atPriority(10)
                .willReturn(json(404, "{\"code\":\"catalog.product-not-found\"}")));
        // S226: warehouse time zones for receipt date rules.
        wm.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/nodes")).willReturn(json(200,
                "[{\"id\":\"HOU-FC\",\"name\":\"Houston FC\",\"kind\":\"FULFILMENT_CENTRE\",\"country\":\"US\","
                        + "\"timezone\":\"America/Chicago\",\"active\":true}]")));
    }

    /** A lot inventory answers with for a receipt line. */
    record Lot(String sku, String lotNo, int qty, String status, long unitCost) {
    }

    /** Inventory answers POST /v1/receipts for {@code receiptRef} with these lots. */
    static void receiptAccepted(WireMockServer wm, String receiptRef, List<Lot> lots) {
        StringBuilder body = new StringBuilder("{\"receiptRef\":\"" + receiptRef + "\",\"nodeId\":\"HOU-FC\","
                + "\"ownerOrg\":\"org_afrotransact_retail\",\"replayed\":false,\"lots\":[");
        for (int i = 0; i < lots.size(); i++) {
            Lot l = lots.get(i);
            body.append(i == 0 ? "" : ",").append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"sku\":\"")
                    .append(l.sku()).append("\",\"lotNo\":\"").append(l.lotNo()).append("\",\"nodeId\":\"HOU-FC\",")
                    .append("\"qtyOnHand\":").append(l.qty()).append(",\"status\":\"").append(l.status())
                    .append("\",\"unitLandedCostMinor\":").append(l.unitCost()).append(",\"currency\":\"USD\"}");
        }
        body.append("]}");
        wm.stubFor(receipts(receiptRef).willReturn(json(201, body.toString())));
    }

    static void receiptAnswers(WireMockServer wm, String receiptRef, int status, String problem) {
        wm.stubFor(receipts(receiptRef).willReturn(json(status, problem)));
    }

    private static MappingBuilder receipts(String receiptRef) {
        return WireMock.post(WireMock.urlPathEqualTo("/v1/receipts"))
                .withRequestBody(WireMock.matchingJsonPath("$.receiptRef", WireMock.equalTo(receiptRef)));
    }

    private static String variant(String atin, String ownerKind, String title) {
        return "{\"product\":{\"id\":\"" + UUID.randomUUID() + "\",\"atin\":\"AT00PRD001\",\"ownerKind\":\""
                + ownerKind + "\",\"title\":\"" + title + "\"},\"variant\":{"
                + (atin == null ? "" : "\"atin\":\"" + atin + "\",")
                + "\"sellerSku\":\"JOL-6\",\"gtin\":null,\"priceMinor\":799,\"currency\":\"USD\"}}";
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return WireMock.aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
