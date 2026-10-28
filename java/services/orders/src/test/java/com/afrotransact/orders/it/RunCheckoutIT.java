package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.application.FollowUpService;
import com.afrotransact.orders.application.PaymentEventService;
import com.afrotransact.orders.application.PreorderCancellationService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S261 checkout for a delivery run staff scheduled, end to end (real Temporal worker, WireMock upstreams): AfroTransact
 * stock goes on the next open run for the address with its date, cutoff and fee rule, holds a real inventory
 * reservation and a slot on the run, is charged like any order, and gives both back when the customer cancels, the run
 * is cancelled or the payment fails. A line is never offered both a run and a window.
 */
class RunCheckoutIT extends OrdersIntegrationTest {

    private PaymentEventService paymentEvents;
    private FollowUpService followUps;
    private PreorderCancellationService cancellations;
    private final List<StubMapping> stubs = new ArrayList<>();

    @BeforeEach
    void wire(@Autowired PaymentEventService payments, @Autowired FollowUpService followUpService,
              @Autowired PreorderCancellationService cancellationService) {
        this.paymentEvents = payments;
        this.followUps = followUpService;
        this.cancellations = cancellationService;
    }

    /** The stubs of a test must not leak into the other integration tests that share the WireMock server. */
    @AfterEach
    void removeStubs() {
        stubs.forEach(WIREMOCK::removeStubMapping);
        stubs.clear();
    }

    // ------------------------------------------------------------------ stubs

    private void stub(StubMapping mapping) {
        stubs.add(mapping);
    }

    private String run(String runId, Instant cutoff, LocalDate deliversOn, long fee, int ordersSoFar, boolean full) {
        return "{\"runId\":\"" + runId + "\",\"town\":\"Bastrop\",\"status\":\"OPEN\",\"cutoffAt\":\"" + cutoff
                + "\",\"deliversOn\":\"" + deliversOn + "\",\"timeZone\":\"America/Chicago\",\"feeMinor\":" + fee
                + ",\"currency\":\"USD\",\"dropMode\":\"DOORSTEP\",\"acceptingOrders\":true,\"full\":" + full
                + ",\"minOrders\":10,\"ordersSoFar\":" + ordersSoFar + ",\"feeRule\":{\"method\":"
                + "\"AFROTRANSACT_DELIVERY\",\"ruleId\":\"" + UUID.randomUUID() + "\",\"ruleVersion\":3,"
                + "\"currency\":\"USD\",\"amountMinor\":" + fee + ",\"source\":\"RULE\",\"band\":\"0-60 mi\","
                + "\"distanceMiles\":31.5}}";
    }

    private void openRuns(String... runs) {
        stub(WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/public/runs/available"))
                .withQueryParam("zip", WireMock.equalTo("77002")).willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runs\":[" + String.join(",", runs) + "]}"))));
    }

    private void slotApi(String runId, int reserveStatus, String reserveBody) {
        stub(WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/service/runs/" + runId + "/reservations"))
                .willReturn(WireMock.aResponse().withStatus(reserveStatus).withHeader("Content-Type",
                        reserveStatus >= 400 ? "application/problem+json" : "application/json")
                        .withBody(reserveBody))));
        stub(WIREMOCK.stubFor(WireMock.delete(WireMock.urlPathMatching("/v1/service/runs/" + runId
                + "/reservations/.*")).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}"))));
    }

    private void groups(String orderId) {
        for (String action : List.of("commit", "release")) {
            stub(WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/reservations/" + orderId + "/groups/"
                    + action)).willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type",
                    "application/json").withBody("{\"status\":\"OK\"}"))));
        }
    }

    private JsonNode placeCheckout(String customer, String bearer, String body) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("POST", "/v1/checkout", body, "Idempotency-Key", "idem-" + customer,
                "Authorization", "Bearer " + bearer), 201);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void afroTransactStockGoesOnTheNextOpenRunHoldsStockAndASlotAndGivesBothBackOnCancel() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String stockAtin = id("ATF");
        String sellerAtin = id("ATS");
        String fbaOrg = id("org-fba");
        String sellerOrg = id("org-sel");
        String runId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        Instant cutoff = Instant.now().plus(3, ChronoUnit.DAYS);
        LocalDate deliversOn = LocalDate.now().plusDays(6);
        UPSTREAMS.cart(bearer,
                Upstreams.pkg(fbaOrg, "Lagos Foods", "AFROTRANSACT",
                        Upstreams.line(stockAtin, fbaOrg, "AFROTRANSACT", 1250, 2)),
                Upstreams.pkg(sellerOrg, "Mama Tee", "SELLER",
                        Upstreams.line(sellerAtin, sellerOrg, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(stockAtin, "HOU-FC", 2);
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.capture(paymentId);
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/refunds")), 201,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        openRuns(run(runId, cutoff, deliversOn, 799, 6, false));
        slotApi(runId, 201, "{\"runId\":\"" + runId + "\",\"moved\":false}");
        // The seller's goods are not AfroTransact's: delivery-runs is asked about windows for those alone.
        stub(WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.equalTo(sellerAtin)).willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[{\"atin\":\"" + sellerAtin + "\",\"preorder\":false}]}"))));

        JsonNode created = placeCheckout(customer, bearer, """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"%s:SELLER","option":"carrier"}]}
                """.formatted(sellerOrg));
        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();
        groups(orderId);

        // One run shipment (the stock), one parcel (the seller's goods); the run is shown with its date and cutoff.
        JsonNode runShipment = null;
        for (JsonNode s : created.path("shipments")) {
            if ("PREORDER".equals(s.path("fulfilmentType").asString())) {
                runShipment = s;
            }
        }
        assertThat(runShipment).isNotNull();
        assertThat(created.path("shipments")).hasSize(2);
        assertThat(runShipment.path("preorderWindowId").asString()).isEqualTo(runId);
        assertThat(runShipment.path("preorderFulfilment").asString()).isEqualTo("DOOR");
        assertThat(runShipment.path("promisedBy").asString()).isEqualTo(deliversOn.toString());
        assertThat(runShipment.path("label").asString()).startsWith("Delivery run · delivered " + deliversOn);
        assertThat(runShipment.path("cancellableUntil").asString()).isEqualTo(cutoff.toString());
        assertThat(created.path("preorder").path("kind").asString()).isEqualTo("RUN");
        assertThat(created.path("preorder").path("windowName").asString()).isEqualTo("Bastrop delivery run");
        assertThat(created.path("preorder").path("closesAt").asString()).isEqualTo(cutoff.toString());
        WIREMOCK.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.containing(stockAtin)));

        // A real inventory reservation for the run's stock, per order line.
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].sku", WireMock.equalTo(stockAtin)))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].qty", WireMock.equalTo("2")))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].group")));
        // A slot on exactly this run, once, never rolled to the next run.
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/service/runs/" + runId
                + "/reservations")).withHeader("Authorization", WireMock.equalTo("Bearer " + Upstreams.SERVICE_TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.orderId", WireMock.equalTo(orderId)))
                .withRequestBody(WireMock.matchingJsonPath("$.customerId", WireMock.equalTo(customer)))
                .withRequestBody(WireMock.matchingJsonPath("$.allowNextRun", WireMock.equalTo("false"))));
        // The run's fee is the delivery charge, and the rule behind it is kept on the shipment.
        assertThat(jdbc.sql("select shipping_minor from shipment where id = :id")
                .param("id", UUID.fromString(runShipment.path("shipmentId").asString())).query(Long.class).single())
                .isEqualTo(799);
        JsonNode snapshot = JSON.readTree(jdbc.sql("select delivery_fee_rule::text from shipment where id = :id")
                .param("id", UUID.fromString(runShipment.path("shipmentId").asString())).query(String.class)
                .single());
        assertThat(snapshot.path("method").asString()).isEqualTo("AFROTRANSACT_DELIVERY");
        assertThat(snapshot.path("ruleVersion").asInt()).isEqualTo(3);
        assertThat(snapshot.path("band").asString()).isEqualTo("0-60 mi");
        JsonNode preorder = JSON.readTree(jdbc.sql("select preorder::text from shipment where id = :id")
                .param("id", UUID.fromString(runShipment.path("shipmentId").asString())).query(String.class)
                .single());
        assertThat(preorder.path("source").asString()).isEqualTo("RUN");

        // Paid at order like any order: placed, the reservation confirmed, the run's shipment captured now.
        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/confirm")));
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/capture")).withRequestBody(WireMock.matchingJsonPath("$.shipmentId",
                WireMock.equalTo(runShipment.path("shipmentId").asString()))));
        JsonNode event = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = "
                + "'orders.order.placed' and aggregate_id = :a").param("a", orderId).query(String.class).single());
        assertThat(event.path("shipments")).anySatisfy(s -> {
            assertThat(s.path("windowId").asString()).isEqualTo(runId);
            assertThat(s.path("preorder").path("source").asString()).isEqualTo("RUN");
        });

        // The customer cancels before the cutoff: refunded in full with the delivery fee, the stock and the slot
        // are given back.
        JsonNode cancelled = ok(send("POST", "/v1/orders/" + number + "/shipments/"
                + runShipment.path("shipmentId").asString() + "/cancel", "{\"reason\":\"Changed my mind\"}"));
        String runShipmentId = runShipment.path("shipmentId").asString();
        assertThat(cancelled.path("shipments")).anySatisfy(s -> {
            assertThat(s.path("id").asString()).isEqualTo(runShipmentId);
            assertThat(s.path("status").asString()).isEqualTo("cancelled");
            assertThat(s.path("preorder").path("kind").asString()).isEqualTo("RUN");
            assertThat(s.path("preorder").path("windowId").asString()).isEqualTo(runId);
        });
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/refunds")).withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor",
                WireMock.equalTo("799"))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/groups/release")));
        WIREMOCK.verify(1, WireMock.deleteRequestedFor(WireMock.urlPathEqualTo("/v1/service/runs/" + runId
                + "/reservations/" + orderId)).withHeader("Authorization", WireMock.equalTo("Bearer "
                + Upstreams.SERVICE_TOKEN)));
    }

    @Test
    void packagesMovingWithTheirStockCommitItOnlyWhenTheyLeave() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org-fba");
        String runId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.capture(paymentId);
        openRuns(run(runId, Instant.now().plus(2, ChronoUnit.DAYS), LocalDate.now().plusDays(5), 799, 0, false));
        slotApi(runId, 201, "{}");

        JsonNode created = placeCheckout(customer, bearer, "{\"addressId\":\"addr-1\"}");
        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();
        groups(orderId);
        assertThat(paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null))
                .isTrue();
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        followUps.runDue();
        // Charged at order, but the units are still on the shelf until the package leaves.
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/groups/commit")));
        String shipmentId = created.path("shipments").get(0).path("shipmentId").asString();
        TestSupport.as(TestSupport.admin());
        for (String to : List.of("confirmed", "packed", "ready", "picked_up")) {
            transition(shipmentId, to, null, "run-" + to + "-" + shipmentId);
        }
        followUps.runDue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/groups/commit")));
    }

    @Test
    void aRunThatClosedBeforeTheOrderCouldTakeASlotFailsTheCheckoutBeforeAnyPaymentAndFreesTheStock() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org-fba");
        String runId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        openRuns(run(runId, Instant.now().plus(1, ChronoUnit.HOURS), LocalDate.now().plusDays(4), 799, 3, false));
        slotApi(runId, 409, "{\"code\":\"delivery-runs.run-closed\",\"detail\":\"closed\"}");
        TestSupport.as(TestSupport.customer(customer));

        HttpResponse<String> r = send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\"}", "Idempotency-Key",
                "k-" + customer, "Authorization", "Bearer " + bearer);

        JsonNode p = problem(r, 422, "orders.run-closed");
        assertThat(p.path("detail").asString()).contains("closed for orders").contains("next run");
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.metadata.customer_id", WireMock.equalTo(customer))));
        String orderId = jdbc.sql("select order_id from checkout_request where customer_id = :c")
                .param("c", customer).query(String.class).single();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/release")));
        // The slot was never taken, so there is none to give back.
        WIREMOCK.verify(0, WireMock.deleteRequestedFor(WireMock.urlPathMatching("/v1/service/runs/" + runId
                + "/reservations/.*")));
    }

    @Test
    void aFailedPaymentGivesTheSlotAndTheStockBack() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org-fba");
        String runId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        UPSTREAMS.paymentIntent(customer, paymentId);
        openRuns(run(runId, Instant.now().plus(2, ChronoUnit.DAYS), LocalDate.now().plusDays(5), 799, 0, false));
        slotApi(runId, 201, "{}");

        JsonNode created = placeCheckout(customer, bearer, "{\"addressId\":\"addr-1\"}");
        String orderId = created.path("orderId").asString();
        paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.failed", orderId, "Card declined");

        eventually(() -> !WIREMOCK.findAll(WireMock.deleteRequestedFor(WireMock.urlPathEqualTo(
                "/v1/service/runs/" + runId + "/reservations/" + orderId))).isEmpty(), "slot released");
        eventually(() -> !WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/"
                + orderId + "/release"))).isEmpty(), "stock released");
    }

    @Test
    void aFullRunIsSkippedForTheNextOneAndNoOpenRunLeavesTheLineShippingNow() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org-fba");
        String fullRun = UUID.randomUUID().toString();
        String nextRun = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        LocalDate nextDay = LocalDate.now().plusDays(12);
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        UPSTREAMS.paymentIntent(customer, paymentId);
        openRuns(run(fullRun, Instant.now().plus(1, ChronoUnit.DAYS), LocalDate.now().plusDays(4), 799, 20, true),
                run(nextRun, Instant.now().plus(8, ChronoUnit.DAYS), nextDay, 899, 1, false));
        slotApi(nextRun, 201, "{}");

        JsonNode created = placeCheckout(customer, bearer, "{\"addressId\":\"addr-1\"}");

        JsonNode s = created.path("shipments").get(0);
        assertThat(s.path("fulfilmentType").asString()).isEqualTo("PREORDER");
        assertThat(s.path("preorderWindowId").asString()).isEqualTo(nextRun);
        assertThat(s.path("promisedBy").asString()).isEqualTo(nextDay.toString());
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/service/runs/" + fullRun
                + "/reservations")));

        // Once no run is open the same stock is an ordinary parcel again.
        String other = id("cust");
        String otherBearer = "tok-" + other;
        UPSTREAMS.cart(otherBearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(otherBearer, "addr-1", "TX");
        UPSTREAMS.paymentIntent(other, UUID.randomUUID().toString());
        openRuns();
        JsonNode parcel = placeCheckout(other, otherBearer, "{\"addressId\":\"addr-1\"}");
        assertThat(parcel.path("shipments").get(0).path("fulfilmentType").asString()).isEqualTo("FBA_AFROTRANSACT");
        assertThat(parcel.path("preorder").isNull()).isTrue();
    }

    @Test
    void cancellingARunRefundsItsOrdersReleasesTheirStockAndSlots() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String atin = id("ATF");
        String org = id("org-fba");
        String runId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Lagos Foods", "AFROTRANSACT",
                Upstreams.line(atin, org, "AFROTRANSACT", 1000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.capture(paymentId);
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/refunds")), 201,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        openRuns(run(runId, Instant.now().plus(2, ChronoUnit.DAYS), LocalDate.now().plusDays(5), 799, 0, false));
        slotApi(runId, 201, "{}");
        JsonNode created = placeCheckout(customer, bearer, "{\"addressId\":\"addr-1\"}");
        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();
        groups(orderId);
        paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null);
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        followUps.runDue();

        // delivery-runs.run.cancelled names the order: orders cancels through the ordinary path (idempotently).
        assertThat(cancellations.cancelOrders(List.of(orderId), "Delivery run cancelled: not enough orders")
                .cancelled()).isEqualTo(1);
        assertThat(cancellations.cancelOrders(List.of(orderId), "Delivery run cancelled: not enough orders")
                .alreadyCancelled()).isEqualTo(1);
        followUps.runDue();

        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payments/" + paymentId
                + "/refunds")).withRequestBody(WireMock.matchingJsonPath("$.lines[0].shippingMinor",
                WireMock.equalTo("799"))));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/groups/release")));
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'orders.preorder.refund_settled' "
                + "and aggregate_id = :a").param("a", orderId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void windowLinesOfAfroTransactStockHoldStockAndASellersOwnGoodsInAWindowDoNot() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String stockAtin = id("ATF");
        String sellerAtin = id("ATS");
        String fbaOrg = id("org-fba");
        String sellerOrg = id("org-sel");
        String window = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.cart(bearer,
                Upstreams.pkg(fbaOrg, "Lagos Foods", "AFROTRANSACT",
                        Upstreams.line(stockAtin, fbaOrg, "AFROTRANSACT", 1250, 3)),
                Upstreams.pkg(sellerOrg, "Mama Tee", "SELLER", Upstreams.line(sellerAtin, sellerOrg, "SELLER", 900, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(stockAtin, "HOU-FC", 3);
        UPSTREAMS.paymentIntent(customer, paymentId);
        UPSTREAMS.capture(paymentId);
        UPSTREAMS.json(WireMock.post(WireMock.urlPathEqualTo("/v1/payments/" + paymentId + "/refunds")), 201,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        String closes = Instant.now().plus(3, ChronoUnit.DAYS).toString();
        String deliversOn = LocalDate.now().plusDays(6).toString();
        // No run is open (the default), so both lines are asked about at the window.
        stub(WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/service/preorders/eligibility"))
                .withQueryParam("atins", WireMock.containing(sellerAtin)).willReturn(WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"items\":["
                                + pre(stockAtin, window, closes, deliversOn) + "," + pre(sellerAtin, window, closes,
                                deliversOn) + "]}"))));

        JsonNode created = placeCheckout(customer, bearer, "{\"addressId\":\"addr-1\"}");
        String orderId = created.path("orderId").asString();
        String number = created.path("orderNumber").asString();
        groups(orderId);

        assertThat(created.path("preorder").path("kind").asString()).isEqualTo("WINDOW");
        // Only AfroTransact's own stock is reserved (one line, three units); the seller's goods are not ours to hold.
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations"))
                .withHeader("Idempotency-Key", WireMock.equalTo(orderId))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].sku", WireMock.equalTo(stockAtin)))
                .withRequestBody(WireMock.matchingJsonPath("$.lines.length()", WireMock.equalTo("1"))));
        // A window order holds no run slot: its shipments are WINDOW packages.
        assertThat(jdbc.sql("select count(*) from shipment where order_id = :o and preorder ->> 'source' = 'RUN'")
                .param("o", UUID.fromString(orderId)).query(Long.class).single()).isZero();

        paymentEvents.onIntentEvent(UUID.randomUUID(), "payments.intent.succeeded", orderId, null);
        eventually(() -> "placed".equals(ok(send("GET", "/v1/checkout/" + number, null)).path("status").asString()),
                "order placed");
        followUps.runDue();

        // Cancelling the window releases the stock for real (the group of the stock line's shipment).
        cancellations.cancelWindow(window, "Pre-order window cancelled: not enough orders");
        followUps.runDue();
        WIREMOCK.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/reservations/" + orderId
                + "/groups/release")));
    }

    private static String pre(String atin, String window, String closes, String deliversOn) {
        return "{\"atin\":\"" + atin + "\",\"preorder\":true,\"windowId\":\"" + window + "\",\"stage\":\"OPEN\","
                + "\"deliversOn\":\"" + deliversOn + "\",\"closesAt\":\"" + closes + "\",\"door\":true,"
                + "\"pickup\":false,\"windowCode\":\"BST-1\",\"windowName\":\"Bastrop Pre-order #1\"}";
    }
}
