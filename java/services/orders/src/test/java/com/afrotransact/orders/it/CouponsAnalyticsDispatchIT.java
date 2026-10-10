package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.application.PaymentEventService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
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
 * S222 coupons in the checkout saga (validate, redeem with Idempotency-Key = order id, release on failure and on
 * cancellation), S231 seller analytics, S233 relayed courier events / dispatch briefs / richer order.placed.
 */
class CouponsAnalyticsDispatchIT extends OrdersIntegrationTest {

    private PaymentEventService paymentEvents;
    private FollowUpService followUps;

    @BeforeEach
    void wire(@Autowired PaymentEventService payments, @Autowired FollowUpService followUpService) {
        this.paymentEvents = payments;
        this.followUps = followUpService;
    }

    private static String offerLine(String atin, String org, String offerId, long price, int qty) {
        return "{\"variantAtin\":\"" + atin + "\",\"offerId\":\"" + offerId + "\",\"productAtin\":\"P" + atin
                + "\",\"title\":\"Item " + atin + "\",\"sellerOrgId\":\"" + org + "\",\"sellerKind\":\"SELLER\","
                + "\"fulfilment\":\"SELLER\",\"priceMinor\":" + price + ",\"currency\":\"USD\",\"quantity\":" + qty
                + ",\"unavailable\":false}";
    }

    private String couponCheckout(String customer, String coupon) {
        return "{\"addressId\":\"addr-1\",\"couponCode\":\"" + coupon + "\"}";
    }

    @Test
    void couponIsValidatedRedeemedOncePerOrderAndReleasedWhenThePaymentFails() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org-sel");
        String atin = id("ATS");
        String offerId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        String redemption = UUID.randomUUID().toString();
        String code = "SAVE" + customer.substring(customer.length() - 4);
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", offerLine(atin, org, offerId, 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/coupons/validate"))
                .withRequestBody(WireMock.matchingJsonPath("$.code", WireMock.equalTo(code))), 200,
                "{\"code\":\"" + code + "\",\"applicable\":true,\"discountMinor\":400,\"totalDiscountMinor\":400}");
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/coupons/redemptions"))
                .withRequestBody(WireMock.matchingJsonPath("$.code", WireMock.equalTo(code))), 200,
                "{\"id\":\"" + redemption + "\",\"code\":\"" + code + "\",\"status\":\"REDEEMED\","
                        + "\"discountMinor\":400,\"shippingDiscountMinor\":0,\"totalDiscountMinor\":400,"
                        + "\"funding\":\"PLATFORM\",\"lines\":[{\"lineRef\":\"" + offerId + "\",\"sellerOrgId\":\"" + org
                        + "\",\"discountMinor\":400}]}");
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/coupons/redemptions/" + redemption + "/release")),
                200, "{\"id\":\"" + redemption + "\",\"status\":\"RELEASED\"}");
        TestSupport.as(TestSupport.customer(customer));

        JsonNode created = ok(send("POST", "/v1/checkout", couponCheckout(customer, code), "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer), 201);
        String orderId = created.path("orderId").asString();
        // items 40.00 − 4.00; tax 8.25 % of 36.00 (330 scaled to 297); carrier standard free above 35.00.
        assertThat(created.path("totalMinor").asLong()).isEqualTo(3600 + 297);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/coupons/validate"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].offerId", WireMock.equalTo(offerId)))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/coupons/redemptions"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withRequestBody(WireMock.matchingJsonPath("$.orderId", WireMock.equalTo(orderId))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo("3897"))));
        assertThat(jdbc.sql("select discount_minor from order_line where order_id = :id")
                .param("id", UUID.fromString(orderId)).query(Long.class).single()).isEqualTo(400);
        assertThat(couponStatus(orderId)).isEqualTo("REDEEMED");

        paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.failed", orderId, "Card declined");
        eventually(() -> "RELEASED".equals(couponStatus(orderId)), "coupon released");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/coupons/redemptions/" + redemption
                + "/release")).withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN)));
    }

    @Test
    void couponThatDoesNotApplyFailsCheckoutBeforeAnythingIsReservedOrCharged() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org-sel");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER",
                offerLine(id("ATS"), org, UUID.randomUUID().toString(), 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/coupons/validate"))
                .withRequestBody(WireMock.matchingJsonPath("$.code", WireMock.equalTo("EXPIRED1"))), 200,
                "{\"code\":\"EXPIRED1\",\"applicable\":false,\"rejection\":\"EXPIRED\","
                        + "\"message\":\"This coupon has expired\"}");
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/checkout", couponCheckout(customer, "EXPIRED1"), "Idempotency-Key", "k-" + customer,
                "Authorization", "Bearer " + bearer), 422, "orders.coupon-not-applicable");
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))));
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/coupons/redemptions"))
                .withRequestBody(WireMock.matchingJsonPath("$.code", WireMock.equalTo("EXPIRED1"))));
    }

    @Test
    void cancellingEveryShipmentReleasesTheOrdersCoupon() {
        String customer = id("cust");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.paymentIntent(customer, paymentId);
        Order order = placedOrder(customer, paymentId, spec(id("org"), FulfilmentType.SELLER_FULFILLED,
                DeliveryMethod.CARRIER));
        String redemption = UUID.randomUUID().toString();
        jdbc.sql("insert into order_coupon (order_id, code, redemption_id, discount_minor, shipping_discount_minor, "
                        + "status, created_at) values (:o, 'WELCOME', :r, 100, 0, 'REDEEMED', now())")
                .param("o", UUID.fromString(order.id())).param("r", redemption).update();
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/coupons/redemptions/" + redemption + "/release")),
                200, "{\"id\":\"" + redemption + "\",\"status\":\"RELEASED\"}");

        TestSupport.as(TestSupport.customer(customer));
        ok(send("POST", "/v1/orders/" + order.number() + "/shipments/" + order.shipments().getFirst().id()
                + "/cancel", "{\"reason\":\"Changed my mind\"}"));
        followUps.runDue();
        assertThat(couponStatus(order.id())).isEqualTo("RELEASED");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/coupons/redemptions/" + redemption
                + "/release")));
    }

    @Test
    void sellerAnalyticsGivesSalesOrdersAndConversionByDayForTheTokensOrg() {
        String org = id("org-an");
        placedOrder(id("cust"), UUID.randomUUID().toString(), new Spec(org, FulfilmentType.SELLER_FULFILLED,
                DeliveryMethod.CARRIER, false, DeliveryPreferences.defaults(), 1500, 2));
        placedOrder(id("cust"), UUID.randomUUID().toString(), new Spec(org, FulfilmentType.FBA_AFROTRANSACT,
                DeliveryMethod.CARRIER, false, DeliveryPreferences.defaults(), 1000, 1));
        // A checkout that never got paid: counts as a checkout, not as an order.
        String pending = UUID.randomUUID().toString();
        jdbc.sql("insert into customer_order (id, number, customer_id, currency, status, items_minor, shipping_minor, "
                        + "tax_minor, discount_minor, total_minor, created_at, updated_at) values (:id, :n, 'c', 'USD', "
                        + "'CANCELLED', 0, 0, 0, 0, 0, now(), now())")
                .param("id", UUID.fromString(pending)).param("n", "AT-2026-" + pending.substring(0, 6)).update();
        jdbc.sql("insert into shipment (id, order_id, seq, seller_org_id, fulfilment_type, delivery_method, "
                        + "preferences, status, shipping_minor, created_at, updated_at) values (:id, :o, 1, :org, "
                        + "'SELLER_FULFILLED', 'CARRIER', '{}'::jsonb, 'CANCELLED', 0, now(), now())")
                .param("id", UUID.randomUUID()).param("o", UUID.fromString(pending)).param("org", org).update();

        TestSupport.as(TestSupport.seller(org));
        JsonNode a = ok(send("GET", "/v1/seller/analytics?from=" + LocalDate.now().minusDays(6) + "&to="
                + LocalDate.now().plusDays(1), null));
        assertThat(a.path("sellerOrgId").asString()).isEqualTo(org);
        assertThat(a.path("days")).hasSize(8);
        assertThat(a.path("totals").path("orders").asInt()).isEqualTo(2);
        assertThat(a.path("totals").path("checkouts").asInt()).isEqualTo(3);
        assertThat(a.path("totals").path("salesMinor").asLong()).isEqualTo(3000 + 1000);
        assertThat(a.path("totals").path("units").asInt()).isEqualTo(3);
        assertThat(a.path("totals").path("conversionRate").asDouble()).isEqualTo(0.6667);
        assertThat(a.path("toShip").asInt()).isEqualTo(2);
        assertThat(a.path("byFulfilment").toString()).contains("AFROTRANSACT", "SELLER");
        assertThat(a.path("topProducts").get(0).path("revenueMinor").asLong()).as("both fixture lines are product P")
                .isEqualTo(4000);

        TestSupport.as(TestSupport.customer(id("cust")));
        assertThat(send("GET", "/v1/seller/analytics", null).statusCode()).isEqualTo(403);
        TestSupport.as(TestSupport.seller(org));
        problem(send("GET", "/v1/seller/analytics?from=2026-01-10&to=2026-01-01", null), 422, "orders.invalid-range");
    }

    @Test
    void fulfilmentRelaysCourierEventsForADriverAndDispatchReadsBriefs() {
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.capture(paymentId);
        Order order = placedOrder(id("cust"), paymentId, new Spec(id("org-fba"), FulfilmentType.RETAIL_1P,
                DeliveryMethod.AFROTRANSACT_DELIVERY, false, DeliveryPreferences.defaults(), 2200, 1));
        String shipment = order.shipments().getFirst().id();

        JsonNode placed = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = "
                + "'orders.order.placed' and aggregate_id = :a").param("a", order.id()).query(String.class).single());
        JsonNode summary = placed.path("shipments").get(0);
        assertThat(summary.path("nodeId").asString()).isEqualTo("HOU-FC");
        assertThat(summary.path("perishable").asBoolean()).isFalse();
        assertThat(summary.path("shipToLine1").asString()).isEqualTo("1 Main St");
        assertThat(summary.has("lat")).isTrue();

        TestSupport.as(TestSupport.fulfilmentOps());
        transition(shipment, "confirmed", null, null);
        transition(shipment, "packed", null, null);
        transition(shipment, "ready", null, null);

        TestSupport.as(new Actor("fulfilment-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("delivery:dispatch")));
        assertThat(ok(send("GET", "/v1/shipments/" + shipment + "/delivery-brief", null)).path("shipmentId")
                .asString()).isEqualTo(shipment);

        Actor relay = new Actor("fulfilment-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("delivery:relay", "orders:update-status"));
        TestSupport.as(relay);
        problem(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"status\":\"picked_up\"}"), 422,
                "orders.driver-id-required");
        JsonNode applied = ok(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"status\":\"picked_up\",\"driverId\":\"driver-42\"}"));
        assertThat(applied.path("outcome").asString()).isEqualTo("applied");
        assertThat(jdbc.sql("select actor_type || ':' || actor_id from shipment_status_change where shipment_id = :s "
                        + "and to_status = 'PICKED_UP'").param("s", UUID.fromString(shipment)).query(String.class)
                .single()).isEqualTo("COURIER:driver-42");

        TestSupport.as(new Actor("other-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("orders:read")));
        assertThat(send("POST", "/v1/shipments/" + shipment + "/courier-events",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"status\":\"in_transit\",\"driverId\":\"d\"}")
                .statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/shipments/" + shipment + "/delivery-brief", null).statusCode()).isEqualTo(403);
    }

    private String couponStatus(String orderId) {
        return jdbc.sql("select status from order_coupon where order_id = :o").param("o", UUID.fromString(orderId))
                .query(String.class).optional().orElse(null);
    }
}
