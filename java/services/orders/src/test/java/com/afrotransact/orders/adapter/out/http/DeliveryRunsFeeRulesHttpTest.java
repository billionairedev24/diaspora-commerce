package com.afrotransact.orders.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.afrotransact.orders.domain.FeeRuleSnapshot;
import com.afrotransact.orders.domain.PreorderFulfilment;
import com.afrotransact.orders.port.PreorderEligibility;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The delivery-runs fee answers as orders-service reads them (ADR 0005 §3), on WireMock. */
class DeliveryRunsFeeRulesHttpTest {

    private static WireMockServer server;

    private HttpDeliveryFeeRules rules;
    private HttpPreorderEligibility eligibility;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        rules = new HttpDeliveryFeeRules(RestClient.builder(), server.baseUrl(), Duration.ofSeconds(2), () -> "svc");
        eligibility = new HttpPreorderEligibility(RestClient.builder(), server.baseUrl(), Duration.ofSeconds(2),
                () -> "svc");
    }

    private static void answer(String method, String path, int status, String body) {
        server.stubFor(WireMock.request(method, WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void theCarrierRuleBecomesASnapshotAndTheRequestCarriesTheQuoteAndSubtotal() {
        answer("POST", "/v1/service/delivery-fees/carrier", 200, """
                {"applies":true,"fee":{"method":"CARRIER","ruleId":"7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a11",
                 "ruleVersion":4,"currency":"USD","amountMinor":1350,"source":"CARRIER_QUOTE","band":null,
                 "distanceMiles":null}}""");

        Optional<FeeRuleSnapshot> fee = rules.carrier("USD", 1150L, 2000);

        assertThat(fee).contains(new FeeRuleSnapshot("CARRIER", "7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a11", 4, "USD",
                1350, "CARRIER_QUOTE", null, null));
        server.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/service/delivery-fees/carrier"))
                .withHeader("Authorization", WireMock.equalTo("Bearer svc"))
                .withRequestBody(WireMock.matchingJsonPath("$.currency", WireMock.equalTo("USD")))
                .withRequestBody(WireMock.matchingJsonPath("$.carrierQuoteMinor", WireMock.equalTo("1150")))
                .withRequestBody(WireMock.matchingJsonPath("$.itemsMinor", WireMock.equalTo("2000"))));
    }

    @Test
    void noRuleOrAnUnavailableDeliveryRunsMeansTheCheckoutKeepsItsFlatRate() {
        answer("POST", "/v1/service/delivery-fees/carrier", 200, "{\"applies\":false}");
        assertThat(rules.carrier("EUR", null, 1000)).isEmpty();
        answer("POST", "/v1/service/delivery-fees/carrier", 503, "{\"code\":\"delivery-runs.down\"}");
        assertThat(rules.carrier("USD", null, 1000)).isEmpty();
    }

    @Test
    void aWindowLineKeepsTheRuleBehindItsDoorAndPickupFees() {
        answer("GET", "/v1/service/preorders/eligibility", 200, """
                {"items":[{"atin":"EGU","preorder":true,"windowId":"win-1","stage":"OPEN","deliversOn":"2026-10-30",
                 "closesAt":"2026-10-28T04:59:00Z","door":true,"pickup":true,"doorFeeMinor":899,"pickupFeeMinor":100,
                 "currency":"USD","windowCode":"LBK-7","windowName":"Lubbock Pre-order #7",
                 "doorFeeRule":{"method":"AFROTRANSACT_DELIVERY","ruleId":"7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a11",
                  "ruleVersion":3,"currency":"USD","amountMinor":899,"source":"RULE","band":"10–40 mi",
                  "distanceMiles":32.0},
                 "pickupFeeRule":{"method":"PICKUP","ruleId":"7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a12","ruleVersion":2,
                  "currency":"USD","amountMinor":100,"source":"WINDOW_OVERRIDE"}}]}""");

        PreorderEligibility.Line line = eligibility.eligibility("US", "TX", "Lubbock", List.of("EGU"))
                .get("EGU");

        assertThat(line.doorFeeMinor()).isEqualTo(899);
        FeeRuleSnapshot door = line.feeRule(PreorderFulfilment.DOOR);
        assertThat(door.method()).isEqualTo("AFROTRANSACT_DELIVERY");
        assertThat(door.ruleVersion()).isEqualTo(3);
        assertThat(door.band()).isEqualTo("10–40 mi");
        assertThat(door.distanceMiles()).isEqualByComparingTo("32");
        FeeRuleSnapshot pickup = line.feeRule(PreorderFulfilment.PICKUP);
        assertThat(pickup.source()).isEqualTo("WINDOW_OVERRIDE");
        assertThat(pickup.band()).isNull();
    }

    @Test
    void anAnswerWithoutRulesStillWorksAndCarriesNone() {
        answer("GET", "/v1/service/preorders/eligibility", 200, """
                {"items":[{"atin":"EGU","preorder":true,"windowId":"win-1","stage":"OPEN","deliversOn":"2026-10-30",
                 "door":true,"pickup":false,"doorFeeMinor":799}]}""");

        PreorderEligibility.Line line = eligibility.eligibility("US", "TX", "Lubbock", List.of("EGU"))
                .get("EGU");

        assertThat(line.doorFeeMinor()).isEqualTo(799);
        assertThat(line.doorFeeRule()).isNull();
        assertThat(line.pickupFeeRule()).isNull();
    }
}
