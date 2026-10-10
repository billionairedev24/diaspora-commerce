package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.application.PaymentEventService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S235 checkout tax from tax-returns (with table fallback) and the richer orders.order.placed, S229 the service order
 * lookup, S218 per-shipment inventory release / settlement and return links on the order view.
 */
class ReturnsTaxLookupIT extends OrdersIntegrationTest {

    private static final Actor TAX_RETURNS = new Actor("svc-tax-returns", PrincipalType.SERVICE, Optional.empty(),
            Set.of(), Set.of("orders:lookup"));

    private PaymentEventService paymentEvents;
    private FollowUpService followUps;

    @BeforeEach
    void wire(@Autowired PaymentEventService service, @Autowired FollowUpService followUpService) {
        this.paymentEvents = service;
        this.followUps = followUpService;
    }

    /** tax-returns answers 1.00 tax per line with a local jurisdiction for New York addresses only. */
    private static void taxReturnsQuotesNewYork() {
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/tax/quotes"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.shipTo.state", WireMock.equalTo("NY")))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("{\"provider\":\"rules\",\"currency\":\"USD\",\"lines\":["
                                + "{{#each (jsonPath request.body '$.lines') as |l|}}{{#unless @first}},{{/unless}}"
                                + "{\"lineId\":\"{{l.lineId}}\",\"taxMinor\":100,\"shippingTaxMinor\":0,"
                                + "\"jurisdiction\":\"US-NY\",\"localJurisdiction\":\"US-NY-100\"}{{/each}}]}")));
    }

    @Test
    void checkoutTaxComesFromTaxReturnsAndThePlacedEventCarriesWhatReturnsNeed() {
        taxReturnsQuotesNewYork();
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String fbaAtin = id("ATF");
        String sellerAtin = id("ATS");
        String fbaOrg = id("org-fba");
        String sellerOrg = id("org-sel");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer,
                Upstreams.pkg(fbaOrg, "Lagos Foods", "AFROTRANSACT",
                        Upstreams.line(fbaAtin, fbaOrg, "AFROTRANSACT", 1250, 2)),
                Upstreams.pkg(sellerOrg, "Mama Tee", "SELLER",
                        Upstreams.line(sellerAtin, sellerOrg, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-ny", "NY");
        UPSTREAMS.perishable(fbaAtin);
        UPSTREAMS.reservation(fbaAtin, "EWR-FC", 2);
        UPSTREAMS.paymentIntent(customer, paymentId);
        TestSupport.as(TestSupport.customer(customer));
        String request = """
                {"addressId":"addr-ny","deliverySelections":[
                  {"packageId":"%s","option":"afrotransact_delivery"},{"packageId":"%s","option":"carrier"}]}
                """.formatted(fbaOrg + ":AFROTRANSACT", sellerOrg + ":SELLER");

        JsonNode created = ok(send("POST", "/v1/checkout", request, "Idempotency-Key", "idem-" + customer,
                "Authorization", "Bearer " + bearer), 201);
        String orderId = created.path("orderId").asString();
        // items 25.00 + 40.00, AfroTransact Delivery 4.99, carrier free above 35.00; tax 1.00 per line from the quote
        assertThat(created.path("totalMinor").asLong()).isEqualTo(2500 + 4000 + 499 + 200);
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/tax/quotes"))
                .withRequestBody(WireMock.matchingJsonPath("$.shipTo.postalCode", WireMock.equalTo("77002")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[?(@.atin == '" + fbaAtin
                        + "' && @.shippingMinor == 499 && @.productType == 'perishable')]"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[?(@.atin == '" + sellerAtin
                        + "' && @.shippingMinor == 0)]")));
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].group")));

        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        eventually(() -> outbox("orders.order.placed", orderId) == 1, "order placed");
        JsonNode placed = JSON.readTree(jdbc.sql("""
                select payload::text from platform_outbox where type = 'orders.order.placed' and aggregate_id = :id
                """).param("id", orderId).query(String.class).single());
        assertThat(placed.path("shipToLine1").asString()).isEqualTo("1 Main St");
        assertThat(placed.path("shipToName").asString()).isEqualTo("Ada Obi");
        assertThat(placed.path("paymentId").asString()).isEqualTo(paymentId);
        JsonNode fba = placed.path("shipments").get(0);
        assertThat(fba.path("shippingMinor").asLong()).isEqualTo(499);
        assertThat(fba.path("shipBy").asString()).isNotBlank();
        JsonNode fbaLine = fba.path("lines").get(0);
        assertThat(fbaLine.path("productType").asString()).isEqualTo("perishable");
        assertThat(fbaLine.path("perishable").asBoolean()).isTrue();
        assertThat(fbaLine.path("taxMinor").asLong()).isEqualTo(100);
        assertThat(fbaLine.path("taxJurisdiction").asString()).isEqualTo("US-NY");
        assertThat(fbaLine.path("localTaxJurisdiction").asString()).isEqualTo("US-NY-100");
        assertThat(fbaLine.path("discountMinor").asLong()).isZero();
        assertThat(placed.path("shipments").get(1).path("shippingMinor").asLong()).isZero();
    }

    @Test
    void checkoutFallsBackToTheTaxTableWhenTaxReturnsIsDown() {
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/tax/quotes"))
                .withRequestBody(WireMock.matchingJsonPath("$.shipTo.state", WireMock.equalTo("NJ")))
                .willReturn(WireMock.aResponse().withStatus(503).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"code\":\"tax-returns.down\"}")));
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String sellerAtin = id("ATS");
        String sellerOrg = id("org-sel");
        UPSTREAMS.cart(bearer, Upstreams.pkg(sellerOrg, "Mama Tee", "SELLER",
                Upstreams.line(sellerAtin, sellerOrg, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-nj", "NJ");
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());
        TestSupport.as(TestSupport.customer(customer));
        JsonNode created = ok(send("POST", "/v1/checkout", """
                {"addressId":"addr-nj","deliverySelections":[{"packageId":"%s","option":"carrier"}]}
                """.formatted(sellerOrg + ":SELLER"), "Idempotency-Key", "idem-" + customer,
                "Authorization", "Bearer " + bearer), 201);
        // NJ 6.63 % from at.orders.tax.rates: 4000 × 0.0663 = 265.2 → 265
        assertThat(created.path("totalMinor").asLong()).isEqualTo(4000 + 265);
    }

    @Test
    void servicesLookUpAnOrderWithPaymentLinesAndCustomer() {
        String customer = id("cust");
        String paymentId = UUID.randomUUID().toString();
        Order order = placedOrder(customer, paymentId,
                spec(id("org-a"), FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER),
                spec(id("org-b"), FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER));

        TestSupport.as(TAX_RETURNS);
        JsonNode found = ok(send("GET", "/v1/lookup/orders/" + order.number(), null));
        assertThat(found.path("orderId").asString()).isEqualTo(order.id());
        assertThat(found.path("customerId").asString()).isEqualTo(customer);
        assertThat(found.path("paymentId").asString()).isEqualTo(paymentId);
        assertThat(found.path("shipTo").path("line1").asString()).isEqualTo("1 Main St");
        assertThat(found.path("shipments")).hasSize(2);
        JsonNode first = found.path("shipments").get(0);
        assertThat(first.path("shipBy").asString()).isNotBlank();
        assertThat(first.path("lines").get(0).path("lineId").asString())
                .isEqualTo(order.shipments().getFirst().lines().getFirst().id());
        assertThat(first.path("lines").get(0).path("shippingMinor").asLong()).isEqualTo(599);
        assertThat(ok(send("GET", "/v1/lookup/orders/" + order.id(), null)).path("orderNumber").asString())
                .isEqualTo(order.number());
        assertThat(found.toString()).doesNotContain("4821");

        TestSupport.as(TestSupport.customer(customer));
        problem(send("GET", "/v1/lookup/orders/" + order.number(), null), 403, "orders.lookup-forbidden");
        TestSupport.as(TAX_RETURNS);
        problem(send("GET", "/v1/lookup/orders/AT-2026-NOPE00", null), 404, "orders.order-not-found");
    }

    @Test
    void cancellingOneAfroTransactShipmentReleasesOnlyItsLinesAndDeliveriesSettlePerShipment() {
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        Order order = placedOrder(id("cust"), paymentId,
                spec(id("org-a"), FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER),
                spec(id("org-b"), FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER),
                spec(id("org-c"), FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER));
        String cancelled = order.shipments().get(0).id();
        String shipped = order.shipments().get(1).id();

        TestSupport.as(TestSupport.admin());
        transition(cancelled, "cancelled", "Damaged in the FC", null);
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + order.id()
                + "/groups/release")).withHeader("Idempotency-Key", WireMock.equalTo("release-" + cancelled))
                .withRequestBody(WireMock.matchingJsonPath("$.groups[0]",
                        WireMock.equalTo(order.shipments().get(0).lines().getFirst().id()))));
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + order.id()
                + "/release")));

        TestSupport.as(TestSupport.fulfilmentOps());
        for (String step : new String[] {"confirmed", "packed", "ready", "picked_up", "in_transit", "delivered"}) {
            assertThat(transition(shipped, step, null, null).path("outcome").asString()).isEqualTo("applied");
        }
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + order.id()
                + "/groups/commit")).withRequestBody(WireMock.matchingJsonPath("$.groups[0]",
                WireMock.equalTo(order.shipments().get(1).lines().getFirst().id()))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/settlements/" + order.id()
                + "/shipments/" + shipped + "/delivered")));
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/settlements/" + order.id()
                + "/delivered")));

        TestSupport.reset();
        JsonNode view = ok(send("GET", "/v1/orders/" + order.number(), null));
        JsonNode delivered = view.path("shipments").get(1);
        assertThat(delivered.path("returnable").asBoolean()).isTrue();
        assertThat(delivered.path("returnWindowEndsAt").asString()).isNotBlank();
        assertThat(delivered.path("returns").path("eligibility").asString())
                .isEqualTo("/v1/returns/eligibility?orderId=" + order.id());
        assertThat(view.path("shipments").get(2).path("returnable").asBoolean()).isFalse();
        assertThat(List.of(view.path("shipments").get(2).path("returns").isNull())).containsExactly(true);
    }
}
