package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.application.LatePreorderService;
import com.afrotransact.orders.application.PaymentEventService;
import com.afrotransact.orders.application.PreorderDeliveryService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S272 pre-order checkout end to end (real Temporal worker, WireMock upstreams): a mixed cart splits into pre-order
 * and ships-now packages under one payment, door costs the window's fee and pickup is free, pre-orders are charged at
 * checkout and refunded in full (delivery fee included) when cancelled before the window closes, upcoming / closed
 * windows block checkout, attribution reaches orders.order.placed, late adds on a customer's behalf and missed
 * deliveries.
 */
class PreorderCheckoutIT extends OrdersIntegrationTest {

    private static final Actor DELIVERY_RUNS = new Actor("svc-delivery-runs", PrincipalType.SERVICE,
            Optional.empty(), Set.of(), Set.of("orders:on-behalf"));

    private PaymentEventService paymentEvents;
    private FollowUpService followUps;
    private PreorderDeliveryService deliveries;
    private LatePreorderService lateAdds;

    @BeforeEach
    void wire(@Autowired PaymentEventService payments, @Autowired FollowUpService followUpService,
              @Autowired PreorderDeliveryService preorderDeliveries, @Autowired LatePreorderService lateAddService) {
        this.paymentEvents = payments;
        this.followUps = followUpService;
        this.deliveries = preorderDeliveries;
        this.lateAdds = lateAddService;
    }

    // ------------------------------------------------------------------ stubs

    /** delivery-runs eligibility for requests naming {@code anyAtin}; entries are JSON objects. */
    private static void eligibility(String anyAtin, String... entries) {
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.containing(anyAtin))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN)), 200,
                "{\"items\":[" + String.join(",", entries) + "]}");
    }

    private static String open(String atin, String windowId, String stage, Instant closesAt, LocalDate deliversOn,
                               String extra) {
        return "{\"atin\":\"" + atin + "\",\"preorder\":true,\"windowId\":\"" + windowId + "\",\"stage\":\"" + stage
                + "\",\"deliversOn\":\"" + deliversOn + "\",\"closesAt\":\"" + closesAt + "\",\"door\":true,"
                + "\"pickup\":true,\"windowCode\":\"LBK-7\",\"windowName\":\"Lubbock Pre-order #7\"" + extra + "}";
    }

    private static String shipsNow(String atin) {
        return "{\"atin\":\"" + atin + "\",\"preorder\":false}";
    }

    private static void refunds(String paymentId) {
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/refunds")), 201,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
    }

    private String payload(String type, String orderId) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :a")
                .param("t", type).param("a", orderId).query(String.class).list().getFirst();
    }

    private JsonNode placeCheckout(String customer, String bearer, String body) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("POST", "/v1/checkout", body, "Idempotency-Key", "idem-" + customer,
                "Authorization", "Bearer " + bearer), 201);
    }

    // ------------------------------------------------------------------ checkout

    @Test
    void mixedCartSplitsIntoPreorderAndShipsNowPackagesUnderOnePaymentAndRefundsUntilClose() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String preA = id("ATA");
        String preB = id("ATB");
        String now = id("ATC");
        String orgA = id("org-a");
        String orgB = id("org-b");
        String orgC = id("org-c");
        String window = "win-" + UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        LocalDate deliversOn = LocalDate.now().plusDays(6);
        UPSTREAMS.cart(bearer,
                Upstreams.pkg(orgA, "Mama Tee", "SELLER", Upstreams.line(preA, orgA, "SELLER", 1000, 2)),
                Upstreams.pkg(orgB, "Kano Spice", "SELLER", Upstreams.line(preB, orgB, "SELLER", 500, 1)),
                Upstreams.pkg(orgC, "Shea House", "SELLER", Upstreams.line(now, orgC, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        eligibility(preA, open(preA, window, "OPEN", Instant.now().plus(3, ChronoUnit.DAYS), deliversOn,
                ",\"allowedMethods\":[\"CARD\",\"WALLET\"]"), open(preB, window, "OPEN",
                Instant.now().plus(3, ChronoUnit.DAYS), deliversOn, ",\"allowedMethods\":[\"CARD\",\"WALLET\"]"),
                shipsNow(now));
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.capture(paymentId);
        refunds(paymentId);

        JsonNode created = placeCheckout(customer, bearer, """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"%s:SELLER","option":"carrier"}],
                 "attribution":{"src":"ig","cr":"STORY","po":"LBK-7"}}
                """.formatted(orgC));
        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();
        JsonNode shipments = created.path("shipments");
        assertThat(shipments).hasSize(3);
        JsonNode youreIn = created.path("preorder");
        assertThat(youreIn.path("windowName").asString()).isEqualTo("Lubbock Pre-order #7");
        assertThat(youreIn.path("number").asString()).isEqualTo(created.path("orderNumber").asString());
        assertThat(youreIn.path("deliversOn").asString()).isEqualTo(deliversOn.toString());
        assertThat(youreIn.path("otherItemCount").asInt()).isEqualTo(1);
        assertThat(youreIn.path("timeZone").asString()).isNotBlank();
        List<JsonNode> pre = new ArrayList<>();
        shipments.forEach(s -> {
            if ("PREORDER".equals(s.path("fulfilmentType").asString())) {
                pre.add(s);
            }
        });
        assertThat(pre).hasSize(2).allSatisfy(s -> {
            assertThat(s.path("preorderWindowId").asString()).isEqualTo(window);
            assertThat(s.path("preorderFulfilment").asString()).isEqualTo("DOOR");
            assertThat(s.path("deliveryMethod").asString()).isEqualTo("AFROTRANSACT_DELIVERY");
            assertThat(s.path("promisedBy").asString()).isEqualTo(deliversOn.toString());
            assertThat(s.path("label").asString()).startsWith("Pre-order · delivered " + deliversOn);
        });

        // One PaymentIntent for the whole cart: the door fee (default $7.99) spread over the window's shipments by
        // items (2000 : 500 → 639 + 160) on PREORDER lines; methods limited to the window's (card, link).
        LoggedRequest intent = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))).getFirst();
        JsonNode body = JSON.readTree(intent.getBodyAsString());
        long preorderShipping = 0;
        int preorderLines = 0;
        for (JsonNode l : body.path("lines")) {
            if ("PREORDER".equals(l.path("fulfilment").asString())) {
                preorderShipping += l.path("shippingMinor").asLong();
                preorderLines++;
            }
        }
        assertThat(preorderLines).isEqualTo(2);
        assertThat(preorderShipping).isEqualTo(799);
        assertThat(body.path("paymentMethodTypes").get(0).asString()).isEqualTo("card");
        assertThat(body.path("paymentMethodTypes").get(1).asString()).isEqualTo("link");
        assertThat(body.path("paymentMethodTypes")).hasSize(2);
        assertThat(body.path("amountMinor").asLong()).isEqualTo(created.path("totalMinor").asLong());

        // Payment authorised → placed; the pre-order shipments are charged now (captured), the parcel later.
        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        String placed = payload("orders.order.placed", orderId);
        JsonNode event = JSON.readTree(placed);
        assertThat(event.path("attribution").path("src").asString()).isEqualTo("ig");
        assertThat(event.path("attribution").path("cr").asString()).isEqualTo("STORY");
        assertThat(event.path("attribution").path("po").asString()).isEqualTo("LBK-7");
        assertThat(event.path("channel").asString()).isEqualTo("INSTAGRAM");
        assertThat(event.path("shipments")).anySatisfy(s -> {
            assertThat(s.path("windowId").asString()).isEqualTo(window);
            assertThat(s.path("fulfilment").asString()).isEqualTo("DOOR");
            assertThat(s.path("preorder").path("code").asString()).isEqualTo("LBK-7");
            assertThat(s.path("preorder").path("name").asString()).isEqualTo("Lubbock Pre-order #7");
            assertThat(s.path("preorder").path("feeMinor").asLong()).isEqualTo(799);
        });
        followUps.runDue();
        for (JsonNode s : pre) {
            WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                    + "/capture")).withRequestBody(WireMock.matchingJsonPath("$.shipmentId",
                    WireMock.equalTo(s.path("shipmentId").asString()))));
        }

        // Cancelling a pre-order shipment before close refunds its items and its share of the delivery fee.
        JsonNode bigger = pre.stream().filter(s -> jdbc.sql("select shipping_minor from shipment where id = :id")
                .param("id", UUID.fromString(s.path("shipmentId").asString())).query(Long.class).single() == 639)
                .findFirst().orElseThrow();
        JsonNode cancelled = ok(send("POST", "/v1/orders/" + number + "/shipments/"
                + bigger.path("shipmentId").asString() + "/cancel", "{\"reason\":\"Changed my mind\"}"));
        assertThat(cancelled.path("shipments")).anySatisfy(s -> {
            assertThat(s.path("id").asString()).isEqualTo(bigger.path("shipmentId").asString());
            assertThat(s.path("status").asString()).isEqualTo("cancelled");
            assertThat(s.path("preorder").path("windowId").asString()).isEqualTo(window);
        });
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/refunds")).withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor",
                WireMock.equalTo("639"))).withRequestBody(WireMock.matchingJsonPath("$.lines[0].itemsMinor",
                WireMock.equalTo("2000"))));

        // After the window closed the customer can no longer cancel the other pre-order shipment.
        JsonNode other = pre.stream().filter(s -> s != bigger).findFirst().orElseThrow();
        jdbc.sql("update shipment set preorder = jsonb_set(preorder, '{closesAt}', '\"2020-01-01T00:00:00Z\"') "
                + "where id = :id").param("id", UUID.fromString(other.path("shipmentId").asString())).update();
        problem(send("POST", "/v1/orders/" + number + "/shipments/" + other.path("shipmentId").asString()
                + "/cancel", "{}"), 409, "orders.preorder-closed");
    }

    @Test
    void pickupIsFreeAndChosenPerWindow() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATP");
        String org = id("org-p");
        String window = "win-" + UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 1500, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        eligibility(atin, open(atin, window, "OPEN", Instant.now().plus(2, ChronoUnit.DAYS),
                LocalDate.now().plusDays(4), ",\"doorFee\":650,\"pickupFeeMinor\":0,\"pickupPointId\":\"pp-lbk\""));
        UPSTREAMS.paymentIntent(customer, paymentId);

        JsonNode created = placeCheckout(customer, bearer, """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"preorder:%s","option":"pickup"}]}
                """.formatted(window));
        JsonNode s = created.path("shipments").get(0);
        assertThat(s.path("deliveryMethod").asString()).isEqualTo("PICKUP");
        assertThat(s.path("preorderFulfilment").asString()).isEqualTo("PICKUP");
        assertThat(jdbc.sql("select shipping_minor from shipment where id = :id")
                .param("id", UUID.fromString(s.path("shipmentId").asString())).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select pickup_location_id from shipment where id = :id")
                .param("id", UUID.fromString(s.path("shipmentId").asString())).query(String.class).single())
                .isEqualTo("pp-lbk");
        // Every method allowed (default): automatic payment methods, nothing restricted.
        JsonNode body = JSON.readTree(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payments")).withHeader("Idempotency-Key", WireMock.equalTo(created.path("orderId")
                .asString()))).getFirst().getBodyAsString());
        assertThat(body.has("paymentMethodTypes")).isFalse();
    }

    @Test
    void doorUsesTheWindowsConfiguredFee() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATD");
        String org = id("org-d");
        String window = "win-" + UUID.randomUUID();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 1500, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        eligibility(atin, open(atin, window, "OPEN", Instant.now().plus(2, ChronoUnit.DAYS),
                LocalDate.now().plusDays(4), ",\"doorFee\":650"));
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());

        JsonNode created = placeCheckout(customer, bearer, "{\"addressId\":\"addr-1\"}");
        assertThat(jdbc.sql("select shipping_minor from shipment where id = :id").param("id",
                UUID.fromString(created.path("shipments").get(0).path("shipmentId").asString()))
                .query(Long.class).single()).isEqualTo(650);
    }

    @Test
    void linesOfAnUpcomingOrClosedWindowBlockCheckout() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATU");
        String org = id("org-u");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 900, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        eligibility(atin, open(atin, "win-up", "UPCOMING", Instant.now().plus(9, ChronoUnit.DAYS),
                LocalDate.now().plusDays(12), ""));
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key", "idem-" + customer,
                "Authorization", "Bearer " + bearer), 422, "orders.preorder-not-open");
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))));
    }

    // ------------------------------------------------------------------ late adds + missed deliveries

    private static void listing(String atin, String org, long price) {
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/public/variants/" + atin)), 200,
                "{\"product\":{\"atin\":\"P" + atin + "\",\"title\":\"Ogbono 1 lb\",\"ownerKind\":\"SELLER\","
                        + "\"sellerOrgId\":\"" + org + "\",\"productType\":\"grocery\",\"attributes\":{}},"
                        + "\"variant\":{\"atin\":\"" + atin + "\",\"priceMinor\":" + price + ",\"currency\":\"USD\"}}");
    }

    private static String lateAdd(String customer, String window, String atin, String payment) {
        return """
                {"customerId":"%s","windowId":"%s","address":{"fullName":"Ada Obi","phone":"+18065550101",
                 "line1":"12 Elm St","city":"Lubbock","state":"TX","postalCode":"79401","country":"US"},
                 "items":[{"atin":"%s","qty":2}],"payment":"%s","reason":"Customer called","note":"Missed the SMS",
                 "actorId":"cs-agent-9","phoneConfirmed":true,"lateAdd":{"linkExpiryHours":12}}
                """.formatted(customer, window, atin, payment);
    }

    @Test
    void lateAddChargesTheSavedCardOffSessionAndMissedDeliveriesOfferRefundOrWait() {
        String customer = id("cust");
        String atin = id("ATL");
        String org = id("org-l");
        String window = "win-" + UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        listing(atin, org, 1200);
        eligibility(atin, open(atin, window, "CLOSED", Instant.now().minus(1, ChronoUnit.HOURS),
                LocalDate.now().plusDays(1), ""));
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer)))
                .withRequestBody(WireMock.matchingJsonPath("$.offSession", WireMock.equalTo("true"))), 201,
                "{\"id\":\"" + paymentId + "\",\"status\":\"AUTHORIZED\",\"amountMinor\":1}");
        UPSTREAMS.capture(paymentId);
        refunds(paymentId);

        // Only delivery-runs (orders:on-behalf) may create orders on a customer's behalf.
        TestSupport.as(TestSupport.customer(customer));
        assertThat(send("POST", "/v1/service/orders/late-preorder", lateAdd(customer, window, atin, "SAVED_CARD"),
                "Idempotency-Key", "late-" + customer).statusCode()).isEqualTo(403);

        TestSupport.as(DELIVERY_RUNS);
        JsonNode created = ok(send("POST", "/v1/service/orders/late-preorder",
                lateAdd(customer, window, atin, "SAVED_CARD"), "Idempotency-Key", "late-" + customer), 201);
        assertThat(created.path("status").asString()).isEqualTo("PLACED");
        assertThat(created.path("channel").asString()).isEqualTo("AGENT_LATE");
        assertThat(created.path("tags").get(0).asString()).isEqualTo("LATE_ADD");
        assertThat(created.path("deliveryFeeMinor").asLong()).isEqualTo(799);
        assertThat(created.path("windowId").asString()).isEqualTo(window);
        String orderId = created.path("orderId").asString();
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/capture")));
        JsonNode replay = ok(send("POST", "/v1/service/orders/late-preorder",
                lateAdd(customer, window, atin, "SAVED_CARD"), "Idempotency-Key", "late-" + customer), 200);
        assertThat(replay.path("orderId").asString()).isEqualTo(orderId);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.shopperId", WireMock.equalTo(customer)))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].fulfilment", WireMock.equalTo("PREORDER"))));
        JsonNode placed = JSON.readTree(payload("orders.order.placed", orderId));
        assertThat(placed.path("channel").asString()).isEqualTo("AGENT_LATE");
        assertThat(placed.path("tags").get(0).asString()).isEqualTo("LATE_ADD");
        assertThat(placed.path("onBehalf").path("actorId").asString()).isEqualTo("cs-agent-9");
        assertThat(placed.path("onBehalf").path("reason").asString()).isEqualTo("Customer called");
        assertThat(placed.path("shipToPhone").asString()).isEqualTo("+18065550101");

        // The delivery date passes undelivered → orders.preorder.delivery_missed, once.
        String shipmentId = created.path("shipmentIds").get(0).asString();
        // Two days back: the job compares with today in the market zone, which trails UTC by up to a day.
        jdbc.sql("update shipment set promised_by = :d where id = :id").param("d", LocalDate.now().minusDays(2))
                .param("id", UUID.fromString(shipmentId)).update();
        assertThat(deliveries.flagMissedDeliveries()).isGreaterThanOrEqualTo(1);
        deliveries.flagMissedDeliveries();
        assertThat(outbox("orders.preorder.delivery_missed", orderId)).isEqualTo(1);
        JsonNode missed = JSON.readTree(payload("orders.preorder.delivery_missed", orderId));
        assertThat(missed.path("choices")).hasSize(2);
        assertThat(missed.path("windowId").asString()).isEqualTo(window);
        assertThat(missed.path("code").asString()).isEqualTo("LBK-7");
        assertThat(missed.path("totalMinor").asLong()).isPositive();

        TestSupport.as(TestSupport.customer(customer));
        JsonNode waited = ok(send("POST", "/v1/orders/" + orderId + "/shipments/" + shipmentId + "/missed-delivery",
                "{\"choice\":\"WAIT\"}"));
        assertThat(waited.path("shipments").get(0).path("preorder").path("missedChoice").asString()).isEqualTo("WAIT");
        JsonNode refunded = ok(send("POST", "/v1/orders/" + orderId + "/shipments/" + shipmentId
                + "/missed-delivery", "{\"choice\":\"REFUND\"}"));
        assertThat(refunded.path("shipments").get(0).path("status").asString()).isEqualTo("cancelled");
        assertThat(outbox("orders.preorder.missed_delivery_answered", orderId)).isEqualTo(2);
        followUps.runDue();
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/refunds")).withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor",
                WireMock.equalTo("799"))));
        problem(send("POST", "/v1/orders/" + orderId + "/shipments/" + shipmentId + "/missed-delivery",
                "{\"choice\":\"WAIT\"}"), 409, "orders.missed-delivery-answered");
    }

    @Test
    void lateAddPaymentLinkWaitsForPaymentAndExpiresUnpaid() {
        String customer = id("cust");
        String atin = id("ATK");
        String org = id("org-k");
        String window = "win-" + UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        listing(atin, org, 800);
        eligibility(atin, open(atin, window, "OPEN", Instant.now().plus(1, ChronoUnit.DAYS),
                LocalDate.now().plusDays(3), ""));
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer)))
                .withRequestBody(WireMock.matchingJsonPath("$.paymentLinkHours", WireMock.equalTo("12"))), 201,
                "{\"id\":\"" + paymentId + "\",\"status\":\"REQUIRES_PAYMENT_METHOD\",\"amountMinor\":1,"
                        + "\"paymentLinkUrl\":\"https://checkout.stripe.com/c/pay/cs_test_1\","
                        + "\"paymentLinkExpiresAt\":\"" + Instant.now().plus(12, ChronoUnit.HOURS) + "\"}");
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/cancel")), 200,
                "{\"id\":\"" + paymentId + "\",\"status\":\"CANCELED\"}");

        TestSupport.as(DELIVERY_RUNS);
        JsonNode created = ok(send("POST", "/v1/service/orders/late-preorder",
                lateAdd(customer, window, atin, "PAYMENT_LINK"), "Idempotency-Key", "late-" + customer), 201);
        assertThat(created.path("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(created.path("payment").asString()).isEqualTo("PAYMENT_LINK");
        assertThat(created.path("paymentLinkUrl").asString()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        String orderId = created.path("orderId").asString();
        JsonNode link = JSON.readTree(payload("orders.preorder.payment_link_created", orderId));
        assertThat(link.path("url").asString()).startsWith("https://checkout.stripe.com/");
        assertThat(link.path("shipToPhone").asString()).isEqualTo("+18065550101");
        assertThat(link.path("windowCode").asString()).isEqualTo("LBK-7");

        // A failed attempt on the link page leaves it payable; nothing is voided yet.
        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.failed", orderId, "declined"))
                .isTrue();
        assertThat(lateAdds.expireUnpaid()).isZero();
        assertThat(jdbc.sql("select status from customer_order where id = :id").param("id", UUID.fromString(orderId))
                .query(String.class).single()).isEqualTo("PENDING_PAYMENT");

        // Unpaid past its expiry: the payment is cancelled and the order voided.
        jdbc.sql("update customer_order set origin = jsonb_set(origin, '{paymentLinkExpiresAt}', "
                + "'\"2020-01-01T00:00:00Z\"') where id = :id").param("id", UUID.fromString(orderId)).update();
        assertThat(lateAdds.expireUnpaid()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select status from customer_order where id = :id").param("id", UUID.fromString(orderId))
                .query(String.class).single()).isEqualTo("CANCELLED");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/cancel")));
    }

    @Test
    void aPaidLinkPlacesTheLateAdd() {
        String customer = id("cust");
        String atin = id("ATM");
        String org = id("org-m");
        String window = "win-" + UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        listing(atin, org, 800);
        eligibility(atin, open(atin, window, "CLOSED", Instant.now().minus(2, ChronoUnit.HOURS),
                LocalDate.now().plusDays(1), ""));
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))), 201,
                "{\"id\":\"" + paymentId + "\",\"status\":\"REQUIRES_PAYMENT_METHOD\",\"amountMinor\":1,"
                        + "\"paymentLinkUrl\":\"https://checkout.stripe.com/c/pay/cs_test_2\","
                        + "\"paymentLinkExpiresAt\":\"" + Instant.now().plus(24, ChronoUnit.HOURS) + "\"}");
        UPSTREAMS.capture(paymentId);
        TestSupport.as(DELIVERY_RUNS);
        String body = lateAdd(customer, window, atin, "PAYMENT_LINK").replace(",\"lateAdd\":{\"linkExpiryHours\":12}",
                "");
        JsonNode created = ok(send("POST", "/v1/service/orders/late-preorder", body, "Idempotency-Key",
                "late-" + customer), 201);
        String orderId = created.path("orderId").asString();
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.paymentLinkHours", WireMock.equalTo("24")))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))));

        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        assertThat(jdbc.sql("select status from customer_order where id = :id").param("id", UUID.fromString(orderId))
                .query(String.class).single()).isEqualTo("PLACED");
        assertThat(outbox("orders.order.placed", orderId)).isEqualTo(1);
    }

    @Test
    void lateAddsRefuseProductsOutsideTheWindowAndDisabledPayments() {
        String customer = id("cust");
        String atin = id("ATX");
        String window = "win-" + UUID.randomUUID();
        listing(atin, id("org-x"), 800);
        eligibility(atin, shipsNow(atin));
        TestSupport.as(DELIVERY_RUNS);
        problem(send("POST", "/v1/service/orders/late-preorder", lateAdd(customer, window, atin, "SAVED_CARD"),
                "Idempotency-Key", "late-x-" + customer), 422, "orders.preorder-not-eligible");
        problem(send("POST", "/v1/service/orders/late-preorder", lateAdd(customer, window, atin, "PAYMENT_LINK")
                        .replace("{\"linkExpiryHours\":12}", "{\"paymentLink\":false}"),
                "Idempotency-Key", "late-y-" + customer), 422, "orders.late-add-payment-disabled");
    }
}
