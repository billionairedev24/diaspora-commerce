package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.orders.application.PaymentEventService;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

/** POST /v1/checkout end to end through the real Temporal worker (in-JVM test server) and WireMock upstreams. */
class CheckoutIT extends OrdersIntegrationTest {

    private PaymentEventService paymentEvents;

    @BeforeEach
    void wire(@Autowired PaymentEventService service) {
        this.paymentEvents = service;
    }

    private String body(String addressId, String fbaPackage, String sellerPackage) {
        return """
                {"addressId":"%s","deliverySelections":[
                  {"packageId":"%s","option":"afrotransact_delivery",
                   "preferences":{"handover":"hand","instructions":"Call at the gate","contact":"call"}},
                  {"packageId":"%s","option":"carrier"}]}
                """.formatted(addressId, fbaPackage, sellerPackage);
    }

    @Test
    void checkoutReservesWithTheOrderIdCreatesTheIntentAndPlacesTheOrderAfterPayment() {
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
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.perishable(fbaAtin);
        UPSTREAMS.reservation(fbaAtin, "HOU-FC", 2);
        UPSTREAMS.paymentIntent(customer, paymentId);
        TestSupport.as(TestSupport.customer(customer));
        String key = "idem-" + customer;
        String request = body("addr-1", fbaOrg + ":AFROTRANSACT", sellerOrg + ":SELLER");

        JsonNode created = ok(send("POST", "/v1/checkout", request, "Idempotency-Key", key,
                "Authorization", "Bearer " + bearer), 201);

        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();
        assertThat(number).matches("AT-\\d{4}-[2-9A-HJ-NP-Z]{6}");
        assertThat(created.path("status").asString()).isEqualTo("awaiting_payment");
        assertThat(created.path("clientSecret").asString()).isEqualTo("pi_" + paymentId + "_secret");
        // items 2×12.50 + 40.00; tax 8.25 % per line (206 + 330); AfroTransact Delivery free above 50? no: 499;
        // carrier standard free above 35.00 → 0.
        assertThat(created.path("totalMinor").asLong()).isEqualTo(2500 + 4000 + 206 + 330 + 499);
        assertThat(created.path("shipments")).hasSize(2);
        JsonNode delivery = created.path("shipments").get(0);
        assertThat(delivery.path("deliveryMethod").asString()).isEqualTo("AFROTRANSACT_DELIVERY");
        assertThat(delivery.path("deliveryCode").asString()).matches("\\d{4}");
        assertThat(created.path("shipments").get(1).path("deliveryCode").isNull()).isTrue();

        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].qty", WireMock.equalTo("2"))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withRequestBody(WireMock.matchingJsonPath("$.amountMinor",
                        WireMock.equalTo(String.valueOf(2500 + 4000 + 206 + 330 + 499))))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[?(@.fulfilment == 'FBA')]"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[?(@.fulfilment == 'SELF')]"))
                .withRequestBody(WireMock.notContaining("saveForOffSession")));
        WIREMOCK.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/cart"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + bearer)));
        assertThat(jdbc.sql("select status from customer_order where id = :id").param("id", UUID.fromString(orderId))
                .query(String.class).single()).isEqualTo("PENDING_PAYMENT");

        // Same key → same order, nothing reserved twice; same key, different body → 409.
        JsonNode replay = ok(send("POST", "/v1/checkout", request, "Idempotency-Key", key,
                "Authorization", "Bearer " + bearer), 201);
        assertThat(replay.path("orderNumber").asString()).isEqualTo(number);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId)));
        problem(send("POST", "/v1/checkout", body("addr-2", fbaOrg + ":AFROTRANSACT", sellerOrg + ":SELLER"),
                "Idempotency-Key", key, "Authorization", "Bearer " + bearer), 409, "orders.idempotency-key-reused");

        // payments.intent.succeeded → saga confirms the reservation, places the order and clears the cart.
        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        eventually(() -> !WIREMOCK.findAll(WireMock.postRequestedFor(
                WireMock.urlPathEqualTo("/v1/carts/" + customer + "/ordered"))).isEmpty(), "cart cleared");
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/carts/" + customer + "/ordered"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.variantAtins[?(@ == '" + fbaAtin + "')]"))
                .withRequestBody(WireMock.matchingJsonPath("$.variantAtins[?(@ == '" + sellerAtin + "')]")));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/confirm")).withHeader("Idempotency-Key", WireMock.equalTo(orderId)));
        assertThat(outbox("orders.order.placed", orderId)).isEqualTo(1);
        assertThat(outbox("orders.shipment.status_changed", orderId)).isEqualTo(2);

        JsonNode order = ok(send("GET", "/v1/orders/" + number, null));
        assertThat(order.path("status").asString()).isEqualTo("placed");
        assertThat(order.path("totalMinor").asLong()).isEqualTo(order.path("itemsMinor").asLong()
                + order.path("shippingMinor").asLong() + order.path("taxMinor").asLong());
        JsonNode first = order.path("shipments").get(0);
        assertThat(first.path("fulfilmentType").asString()).isEqualTo("FBA_AFROTRANSACT");
        assertThat(first.path("nodeId").asString()).isEqualTo("HOU-FC");
        assertThat(first.path("perishable").asBoolean()).isTrue();
        assertThat(first.path("handoverRequired").asBoolean()).isTrue();
        assertThat(first.path("handoverCode").asString()).isEqualTo(delivery.path("deliveryCode").asString());
        assertThat(first.path("preferences").path("instructions").asString()).isEqualTo("Call at the gate");
        assertThat(first.path("status").asString()).isEqualTo("placed");
        assertThat(first.path("cancellable").asBoolean()).isTrue();
        assertThat(order.path("shipments").get(1).path("fulfilmentType").asString()).isEqualTo("SELLER_FULFILLED");

        JsonNode mine = ok(send("GET", "/v1/orders?customer=me&limit=10", null));
        assertThat(mine.path("items")).anySatisfy(o -> assertThat(o.path("orderNumber").asString()).isEqualTo(number));

        // Staff see it without codes; a seller sees only its own shipment.
        TestSupport.as(TestSupport.admin());
        JsonNode staffView = ok(send("GET", "/v1/orders/" + number, null));
        assertThat(staffView.path("shipments").get(0).path("handoverCode").isNull()).isTrue();
        TestSupport.as(TestSupport.seller(sellerOrg));
        JsonNode sellerView = ok(send("GET", "/v1/orders/" + number, null));
        assertThat(sellerView.path("partial").asBoolean()).isTrue();
        assertThat(sellerView.path("shipments")).hasSize(1);
        assertThat(sellerView.path("totalMinor").asLong()).isEqualTo(4000 + 330);
        TestSupport.as(TestSupport.seller(id("org-other")));
        problem(send("GET", "/v1/orders/" + number, null), 404, "orders.order-not-found");
    }

    /** S282: a new method from Stripe's Payment Element the shopper keeps → setup_future_usage on the intent. */
    @Test
    void aNewPaymentMethodTheShopperSavesCreatesTheIntentForOffSessionReuse() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATS");
        String org = id("org-sel");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.paymentIntent(customer, paymentId);
        TestSupport.as(TestSupport.customer(customer));
        String request = """
                {"addressId":"addr-1","savePaymentMethod":true,
                 "deliverySelections":[{"packageId":"%s:SELLER","option":"carrier"}]}
                """.formatted(org);

        JsonNode created = ok(send("POST", "/v1/checkout", request, "Idempotency-Key", "save-" + customer,
                "Authorization", "Bearer " + bearer), 201);

        assertThat(created.path("clientSecret").asString()).isEqualTo("pi_" + paymentId + "_secret");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", WireMock.equalTo(created.path("orderId").asString()))
                .withRequestBody(WireMock.matchingJsonPath("$.saveForOffSession", WireMock.equalTo("true")))
                .withRequestBody(WireMock.matchingJsonPath("$.shopperId", WireMock.equalTo(customer))));
        // Saving is part of the request: the same key without it is another request.
        problem(send("POST", "/v1/checkout", request.replace("\"savePaymentMethod\":true,", ""), "Idempotency-Key",
                "save-" + customer, "Authorization", "Bearer " + bearer), 409, "orders.idempotency-key-reused");
    }

    @Test
    void insufficientStockFailsCheckoutWithoutCreatingAPaymentIntent() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 2)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.insufficient(atin);
        TestSupport.as(TestSupport.customer(customer));

        HttpResponse<String> r = send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer);

        JsonNode p = problem(r, 409, "orders.insufficient-stock");
        assertThat(p.path("detail").asString()).contains(atin);
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))));
        // The same key replays the same failure.
        problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key", "k-" + customer,
                "Authorization", "Bearer " + bearer), 409, "orders.insufficient-stock");
    }

    @Test
    void fulfilledByAfroTransactLinesReserveTheOffersInventorySku() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org");
        String offerId = UUID.randomUUID().toString();
        String atFn = "ATF" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
        String line = Upstreams.line(atin, org, "AFROTRANSACT", 1000, 2);
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                line.substring(0, line.length() - 1) + ",\"offerId\":\"" + offerId + "\"}"));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/offers/" + offerId))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN)), 200,
                "{\"id\":\"" + offerId + "\",\"atin\":\"" + atin + "\",\"sellerOrgId\":\"" + org
                        + "\",\"fulfilment\":\"AFROTRANSACT\",\"inventorySku\":\"" + atFn + "\"}");
        UPSTREAMS.insufficient(atFn);
        TestSupport.as(TestSupport.customer(customer));

        HttpResponse<String> r = send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer);

        // FBA stock is held under the seller's AT-FN code (the offer's inventorySku), not the variant ATIN.
        assertThat(problem(r, 409, "orders.insufficient-stock").path("detail").asString()).contains(atFn);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].sku", WireMock.equalTo(atFn))));
    }

    @Test
    void deactivatedMarketsBlockCheckout() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER",
                Upstreams.line(id("ATS"), org, "SELLER", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-ak", "AK");
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/markets/eligibility"))
                .withQueryParam("state", WireMock.equalTo("AK")), 200,
                "{\"country\":\"US\",\"deliveryAllowed\":false,\"reason\":\"region-disabled\","
                        + "\"message\":\"We don't deliver to Alaska yet\"}");
        TestSupport.as(TestSupport.customer(customer));

        JsonNode p = problem(send("POST", "/v1/checkout", "{\"addressId\":\"addr-ak\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer), 422, "orders.market-disabled");
        assertThat(p.path("detail").asString()).isEqualTo("We don't deliver to Alaska yet");
    }

    @Test
    void checkoutNeedsAnIdempotencyKey() {
        TestSupport.as(TestSupport.customer(id("cust")));
        problem(send("POST", "/v1/checkout", "{\"addressId\":\"a\"}"), 400, "orders.idempotency-key-required");
    }

    @Test
    void failedPaymentReleasesTheReservationAndVoidsTheOrder() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org");
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "EWR-FC", 1);
        UPSTREAMS.paymentIntent(customer, paymentId);
        TestSupport.as(TestSupport.customer(customer));
        JsonNode created = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer), 201);
        String orderId = created.path("orderId").asString();

        paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.failed", orderId, "Card declined");

        eventually(() -> "CANCELLED".equals(jdbc.sql("select status from customer_order where id = :id")
                .param("id", UUID.fromString(orderId)).query(String.class).single()), "order voided");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/release")));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/cancel")));
        assertThat(outbox("orders.order.cancelled", orderId)).isEqualTo(1);
        problem(send("GET", "/v1/checkout/" + created.path("orderNumber").asString(), null), 402,
                "orders.payment-failed");
    }

    // ------------------------------------------------------------------ one open checkout per customer (gap 2)

    private String openCheckoutCart(String customer, String atin, String paymentId) {
        String bearer = "tok-" + customer;
        String org = id("org");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 2)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "EWR-FC", 2);
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.paymentStatus(paymentId, "REQUIRES_PAYMENT_METHOD");
        TestSupport.as(TestSupport.customer(customer));
        return bearer;
    }

    private long openCheckouts(String customer) {
        return jdbc.sql("select count(*) from checkout_request where customer_id = :c and open_checkout")
                .param("c", customer).query(Long.class).single();
    }

    /** The answer was lost (timeout) and the client retried under a new key: the open checkout is resumed. */
    @Test
    void theSameCartUnderTwoKeysGivesOneOrderAndOnePaymentIntent() {
        String customer = id("cust");
        String paymentId = UUID.randomUUID().toString();
        String bearer = openCheckoutCart(customer, id("ATF"), paymentId);

        JsonNode first = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k1-" + customer, "Authorization", "Bearer " + bearer), 201);
        JsonNode second = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k2-" + customer, "Authorization", "Bearer " + bearer), 201);
        // A retry of the first key after a (simulated) timeout: the same order again.
        JsonNode retry = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k1-" + customer, "Authorization", "Bearer " + bearer), 201);

        String orderId = first.path("orderId").asString();
        assertThat(second.path("orderNumber").asString()).isEqualTo(first.path("orderNumber").asString());
        assertThat(second.path("clientSecret").asString()).isEqualTo(first.path("clientSecret").asString());
        assertThat(retry.path("orderNumber").asString()).isEqualTo(first.path("orderNumber").asString());
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId)));
        assertThat(jdbc.sql("select count(*) from customer_order where customer_id = :c and status = "
                + "'PENDING_PAYMENT'").param("c", customer).query(Long.class).single()).isEqualTo(1);
        assertThat(openCheckouts(customer)).isEqualTo(1);
    }

    /** Another cart under another key: the open checkout's intent is cancelled and its stock released first. */
    @Test
    void aDifferentCartSupersedesTheOpenCheckoutSoOnlyOnePaymentIntentIsLive() {
        String customer = id("cust");
        String atin = id("ATF");
        String paymentId = UUID.randomUUID().toString();
        String bearer = openCheckoutCart(customer, atin, paymentId);

        JsonNode first = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k1-" + customer, "Authorization", "Bearer " + bearer), 201);
        String firstOrder = first.path("orderId").asString();
        JsonNode second = ok(send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\",\"lines\":[{\"variantAtin\":\""
                + atin + "\",\"quantity\":1}]}", "Idempotency-Key", "k2-" + customer, "Authorization",
                "Bearer " + bearer), 201);

        assertThat(second.path("orderNumber").asString()).isNotEqualTo(first.path("orderNumber").asString());
        WIREMOCK.verify(WireMock.moreThanOrExactly(1), WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payments/" + paymentId + "/cancel")));
        assertThat(jdbc.sql("select status from customer_order where id = :id").param("id", UUID.fromString(firstOrder))
                .query(String.class).single()).isEqualTo("CANCELLED");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + firstOrder
                + "/release")));
        assertThat(jdbc.sql("select count(*) from customer_order where customer_id = :c and status = "
                + "'PENDING_PAYMENT'").param("c", customer).query(Long.class).single()).isEqualTo(1);
        assertThat(openCheckouts(customer)).isEqualTo(1);
    }

    /** The database itself refuses a second open checkout for a customer (partial unique index). */
    @Test
    void theOpenCheckoutConstraintAllowsOnePerCustomer() {
        String customer = id("cust");
        String insert = """
                insert into checkout_request (customer_id, idem_key, fingerprint, order_id, order_number, workflow_id,
                    status, open_checkout, created_at, updated_at)
                values (:c, :k, 'fp', :o, :n, :w, 'AWAITING_PAYMENT', true, now(), now())
                """;
        String o1 = UUID.randomUUID().toString();
        jdbc.sql(insert).param("c", customer).param("k", "a").param("o", UUID.fromString(o1))
                .param("n", "T-" + o1.substring(0, 8)).param("w", "checkout-" + o1).update();
        String o2 = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbc.sql(insert).param("c", customer).param("k", "b").param("o", UUID.fromString(o2))
                .param("n", "T-" + o2.substring(0, 8)).param("w", "checkout-" + o2).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // Late adds and finished checkouts never hold the slot.
        jdbc.sql(insert.replace("true", "false")).param("c", customer).param("k", "b").param("o", UUID.fromString(o2))
                .param("n", "T-" + o2.substring(0, 8)).param("w", "late-preorder-" + o2).update();
        assertThat(openCheckouts(customer)).isEqualTo(1);
    }
}
