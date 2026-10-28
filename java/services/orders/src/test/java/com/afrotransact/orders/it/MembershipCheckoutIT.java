package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.afrotransact.orders.application.PaymentEventService;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S323: a member's shipping fee is waived through the fee rule resolution of ADR 0005 (payments applies the tier's
 * minimum and cap), the order keeps the tier and benefit on the shipment, the payment is created with the waivers, and
 * a checkout never fails because the membership answer is missing.
 */
class MembershipCheckoutIT extends OrdersIntegrationTest {

    private PaymentEventService paymentEvents;

    @BeforeEach
    void wire(@Autowired PaymentEventService service) {
        this.paymentEvents = service;
    }

    private String carrierBody(String sellerPackage) {
        return """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"%s","option":"carrier"}]}
                """.formatted(sellerPackage);
    }

    /** One seller line of 30.00 (under the flat 35.00 free carrier threshold, so shipping is 5.99). */
    private String cart(String bearer, long price) {
        String atin = id("ATS");
        String org = id("org-sel");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", price,
                1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        return org + ":SELLER";
    }

    /** payments answers that the customer is a Plus member and waives the first fee in full. */
    private void plusMember(String customer) {
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"member\":true,\"tierCode\":\"plus\",\"tierName\":\"Plus\",\"waivers\":["
                                + "{\"key\":\"1\",\"benefit\":\"FREE_SHIPPING\",\"waivedMinor\":599}],"
                                + "\"totalWaivedMinor\":599}")));
    }

    private JsonNode checkout(String customer, String bearer, String sellerPackage) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("POST", "/v1/checkout", carrierBody(sellerPackage), "Idempotency-Key", "idem-" + customer,
                "Authorization", "Bearer " + bearer), 201);
    }

    @Test
    void aMembersShippingFeeIsWaivedAndTheOrderRecordsTheTierAndBenefit() {
        // the same cart for a shopper who is not a member: the flat carrier fee applies
        String other = id("cust");
        String otherBearer = "tok-" + other;
        String otherPackage = cart(otherBearer, 3000);
        UPSTREAMS.paymentIntent(other, UUID.randomUUID().toString());
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(other)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"member\":false,\"waivers\":[],\"totalWaivedMinor\":0}")));
        JsonNode full = checkout(other, otherBearer, otherPackage);
        long fullTotal = full.path("totalMinor").asLong();
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.containing("membershipWaivers")));

        String customer = id("cust");
        String bearer = "tok-" + customer;
        String sellerPackage = cart(bearer, 3000);
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());
        plusMember(customer);
        JsonNode created = checkout(customer, bearer, sellerPackage);
        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();

        assertThat(created.path("totalMinor").asLong()).as("the 5.99 carrier fee is not charged")
                .isEqualTo(fullTotal - 599);
        // orders asked payments with the order's goods and the fee the rules priced for the shipment
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer)))
                .withRequestBody(WireMock.matchingJsonPath("$.itemsMinor", WireMock.equalTo("3000")))
                .withRequestBody(WireMock.matchingJsonPath("$.fees[0].kind", WireMock.equalTo("CARRIER")))
                .withRequestBody(WireMock.matchingJsonPath("$.fees[0].feeMinor", WireMock.equalTo("599"))));
        // and created the payment for the reduced total, with the waiver for payments to record as a benefit used
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor", WireMock.equalTo(String.valueOf(
                        fullTotal - 599))))
                .withRequestBody(WireMock.matchingJsonPath("$.membershipWaivers[0].benefit",
                        WireMock.equalTo("FREE_SHIPPING")))
                .withRequestBody(WireMock.matchingJsonPath("$.membershipWaivers[0].amountMinor",
                        WireMock.equalTo("599")))
                .withRequestBody(WireMock.matchingJsonPath("$.membershipWaivers[0].tierCode",
                        WireMock.equalTo("plus")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor", WireMock.equalTo("0"))));

        // the order records the tier and the benefit that waived the fee, apart from the fee rule snapshot
        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        JsonNode order = ok(send("GET", "/v1/orders/" + number, null));
        JsonNode shipment = order.path("shipments").get(0);
        assertThat(shipment.path("shippingMinor").asLong()).isZero();
        assertThat(shipment.path("membershipWaiver").path("tierName").asString()).isEqualTo("Plus");
        assertThat(shipment.path("membershipWaiver").path("tierCode").asString()).isEqualTo("plus");
        assertThat(shipment.path("membershipWaiver").path("benefit").asString()).isEqualTo("FREE_SHIPPING");
        assertThat(shipment.path("membershipWaiver").path("waivedMinor").asLong()).isEqualTo(599);
        assertThat(order.path("shippingMinor").asLong()).isZero();

        // the seller does not learn who is a member
        String sellerOrg = order.path("shipments").get(0).path("sellerOrgId").asString();
        TestSupport.as(TestSupport.seller(sellerOrg));
        JsonNode sellerView = ok(send("GET", "/v1/orders/" + number, null));
        assertThat(sellerView.path("shipments").get(0).path("membershipWaiver").isNull()).isTrue();
    }

    @Test
    void aShopperWhoIsNotAMemberPaysTheFeeAndNothingIsRecorded() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String sellerPackage = cart(bearer, 3000);
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"member\":false,\"waivers\":[],\"totalWaivedMinor\":0}")));
        JsonNode created = checkout(customer, bearer, sellerPackage);
        assertThat(created.path("totalMinor").asLong()).isGreaterThan(3000 + 599);
        assertThat(jdbc.sql("select count(*) from shipment where order_id = :o and membership_waiver is not null")
                .param("o", UUID.fromString(created.path("orderId").asString())).query(Long.class).single()).isZero();
    }

    @Test
    void aCheckoutNeverFailsBecauseTheMembershipAnswerIsMissing() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String sellerPackage = cart(bearer, 3000);
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer)))
                .willReturn(WireMock.aResponse().withStatus(503).withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"code\":\"payments.down\"}")));
        JsonNode created = checkout(customer, bearer, sellerPackage);
        assertThat(created.path("status").asString()).isEqualTo("awaiting_payment");
        assertThat(created.path("totalMinor").asLong()).as("the customer pays the fee").isGreaterThan(3000 + 599);
    }

    // ------------------------------------------------------------------ pre-order windows

    private static String windowLine(String atin, String windowId, String stage) {
        return "{\"atin\":\"" + atin + "\",\"preorder\":true,\"windowId\":\"" + windowId + "\",\"stage\":\"" + stage
                + "\",\"deliversOn\":\"" + LocalDate.now().plusDays(12) + "\",\"closesAt\":\""
                + Instant.now().plus(9, ChronoUnit.DAYS) + "\",\"door\":true,\"pickup\":false,"
                + "\"windowCode\":\"LBK-7\",\"windowName\":\"Lubbock Pre-order #7\"}";
    }

    private void entitlements(String customer, int earlyAccessHours) {
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/memberships/customers/" + customer
                + "/entitlements")), 200, "{\"member\":true,\"customerId\":\"" + customer
                + "\",\"tierCode\":\"plus\",\"tierName\":\"Plus\",\"earlyAccessHours\":" + earlyAccessHours + "}");
    }

    @Test
    void aMembersEarlyAccessHoursOpenAnUpcomingWindowForThemOnly() {
        String atin = id("ATU");
        String org = id("org-u");
        // delivery-runs says UPCOMING to everyone, and OPEN to a request that names the member's early access hours
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.containing(atin)), 200, "{\"items\":["
                + windowLine(atin, "win-early", "UPCOMING") + "]}");
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.containing(atin))
                .withQueryParam("earlyAccessHours", WireMock.equalTo("24")).atPriority(1), 200, "{\"items\":["
                + windowLine(atin, "win-early", "OPEN") + "]}");

        String shopper = id("cust");
        String shopperBearer = "tok-" + shopper;
        UPSTREAMS.cart(shopperBearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 900,
                1)));
        UPSTREAMS.addresses(shopperBearer, "addr-1", "TX");
        TestSupport.as(TestSupport.customer(shopper));
        problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key", "idem-" + shopper,
                "Authorization", "Bearer " + shopperBearer), 422, "orders.preorder-not-open");

        String member = id("cust");
        String memberBearer = "tok-" + member;
        UPSTREAMS.cart(memberBearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 900,
                1)));
        UPSTREAMS.addresses(memberBearer, "addr-1", "TX");
        UPSTREAMS.paymentIntent(member, UUID.randomUUID().toString());
        entitlements(member, 24);
        TestSupport.as(TestSupport.customer(member));
        JsonNode created = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "idem-" + member, "Authorization", "Bearer " + memberBearer), 201);
        assertThat(created.path("shipments").get(0).path("fulfilmentType").asString()).isEqualTo("PREORDER");
        WIREMOCK.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("earlyAccessHours", WireMock.equalTo("24")));
    }

    @Test
    void aMembersPreorderDoorFeeIsWaivedLikeAnyOtherDeliveryFee() {
        String atin = id("ATW");
        String org = id("org-w");
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.containing(atin)), 200, "{\"items\":["
                + windowLine(atin, "win-fee", "OPEN") + "]}");
        String customer = id("cust");
        String bearer = "tok-" + customer;
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 3000,
                1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"member\":true,\"tierCode\":\"plus\",\"tierName\":\"Plus\",\"waivers\":["
                                + "{\"key\":\"1\",\"benefit\":\"FREE_DELIVERY\",\"waivedMinor\":799}],"
                                + "\"totalWaivedMinor\":799}")));
        TestSupport.as(TestSupport.customer(customer));
        JsonNode created = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "idem-" + customer, "Authorization", "Bearer " + bearer), 201);
        // the window's door fee (7.99 by default) went to payments as an AfroTransact delivery fee and came back waived
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/memberships/waivers"))
                .withRequestBody(WireMock.matchingJsonPath("$.fees[0].kind", WireMock.equalTo("AFROTRANSACT_DELIVERY")))
                .withRequestBody(WireMock.matchingJsonPath("$.fees[0].feeMinor", WireMock.equalTo("799"))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", WireMock.equalTo(created.path("orderId").asString()))
                .withRequestBody(WireMock.matchingJsonPath("$.membershipWaivers[0].benefit",
                        WireMock.equalTo("FREE_DELIVERY")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor", WireMock.equalTo("0"))));
        assertThat(created.path("shipments").get(0).path("shippingMinor").asLong()).isZero();
    }
}
