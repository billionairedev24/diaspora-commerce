package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.payments.application.PaymentsEvents;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/** S051 PaymentIntents, S053 ledger, S054 split, S055 fees, S057 refunds — against PostgreSQL and WireMock Stripe. */
class PaymentLifecycleIT extends PaymentsIntegrationTest {

    @Test
    void capturePerShipmentPostsBalancedJournalsThatMatchTheSplit() {
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("L1", "org_a", "Groceries", "SELF", 2, 2000, 500, 165),
                line("L2", RETAIL, "Groceries", "FBA", 1, 1000, 300, 83),
                line("L3", "org_b", "Fashion", "FBA", 3, 3000, 0, 248)));
        String paymentId = payment.path("id").asString();

        LoggedRequest create = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents")).withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent")))
                .getFirst();
        assertThat(create.getBodyAsString()).contains("amount=7296", "currency=usd", "capture_method=automatic_async",
                "payment_method_options[card][capture_method]=manual", "payment_method_options[klarna][capture_method]=manual",
                "transfer_group=" + order, "payment_method_options[card][request_multicapture]=if_available", "customer=cus_shopper",
                "automatic_payment_methods[enabled]=true", "metadata[order_id]=" + order);
        assertThat(payment.path("clientSecret").asString()).isEqualTo("pi_" + order + "_secret_test");
        assertThat(outbox(PaymentsEvents.INTENT_CREATED, order)).isEqualTo(1);
        assertThat(outbox(PaymentsEvents.INTENT_SUCCEEDED, order)).isEqualTo(1);

        STRIPE.chargeFees("ch_" + order, new long[] {2665, 107});
        JsonNode first = capture(paymentId, order, order + "-S1", List.of("L1"), 2665, 107, false);
        assertThat(first.path("stripeFeeMinor").asLong()).isEqualTo(107);
        assertThat(first.path("feeEstimated").asBoolean()).isFalse();
        assertThat(first.path("paymentStatus").asString()).isEqualTo("PARTIALLY_CAPTURED");
        LoggedRequest cap = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents/pi_" + order + "/capture"))).getFirst();
        assertThat(cap.getHeader("Idempotency-Key")).isEqualTo(order + ":capture:" + order + "-S1");
        assertThat(cap.getBodyAsString()).contains("amount_to_capture=2665", "final_capture=false");

        assertEveryJournalBalances();
        assertThat(balance(order, "2000", "org_a")).isEqualTo(2000 + 500 - 160 - 107);
        assertThat(balance(order, "2100", "US-TX-48201")).isEqualTo(165);
        assertThat(balance(order, "4000", "")).isEqualTo(160);
        assertThat(-balance(order, "1000", "")).isEqualTo(2665 - 107);

        STRIPE.chargeFees("ch_" + order, new long[] {2665, 107}, new long[] {4631, 164});
        JsonNode second = capture(paymentId, order, order + "-S2", List.of("L2", "L3"), 4631, 164, true);
        assertThat(second.path("stripeFeeMinor").asLong()).isEqualTo(164);
        assertThat(second.path("paymentStatus").asString()).isEqualTo("CAPTURED");
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents/pi_" + order + "/capture"))).getLast().getBodyAsString())
                .contains("amount_to_capture=4631", "final_capture=true");
        assertEveryJournalBalances();
        // AfroTransact Retail: no FBA fee, keeps its shipping, net (1000 + 300 − 49 fee share) is 4400 revenue.
        assertThat(balance(order, "2000", RETAIL)).isZero();
        assertThat(balance(order, "4400", "")).isEqualTo(1000 + 300 - 49);
        assertThat(balance(order, "4100", "")).isEqualTo(975);
        assertThat(balance(order, "4200", "")).isZero();
        assertThat(outbox(PaymentsEvents.CAPTURED, order)).isEqualTo(2);

        TestSupport.as(TestSupport.FINANCE);
        JsonNode split = ok(send("GET", "/v1/orders/" + order + "/split", null, null));
        assertThat(split.path("feeSource").asString()).isEqualTo("ACTUAL");
        assertThat(split.path("chargeMinor").asLong()).isEqualTo(7296);
        assertThat(split.path("stripeFeeMinor").asLong()).isEqualTo(271);
        long nets = 0;
        for (JsonNode r : split.path("recipients")) {
            nets += r.path("netMinor").asLong();
            if (r.path("recipient").asString().equals(RETAIL)) {
                assertThat(r.path("commissionMinor").asLong()).isZero();
            }
        }
        assertThat(nets + split.path("taxCollectedMinor").asLong()).isEqualTo(7296 - 271);
        TestSupport.as(TestSupport.seller("org_a", "seller.owner", "payouts:request"));
        problem(send("GET", "/v1/orders/" + order + "/split", null, null), 403, "auth.missing-permission");
    }

    @Test
    void partialRefundReversesSellerPayableAndCommission() {
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("L1", "org_c", "Groceries", "SELF", 1, 10_000, 0, 825)));
        String paymentId = payment.path("id").asString();
        STRIPE.chargeFees("ch_" + order, new long[] {10_825, 320});
        capture(paymentId, order, order + "-S1", List.of(), 10_825, 320, true);
        assertThat(balance(order, "2000", "org_c")).isEqualTo(10_000 - 800 - 320);

        STRIPE.refund("pi_" + order, "re_" + order, 5412, "succeeded");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        Map<String, Object> body = Map.of("reason", "damaged", "lines", List.of(Map.of("lineId", "L1",
                "itemsMinor", 5000, "taxMinor", 412)));
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", body, "refund-" + order), 201);
        assertThat(refund.path("amountMinor").asLong()).isEqualTo(5412);
        assertThat(refund.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(refund.path("lines").get(0).path("commissionReversedMinor").asLong()).isEqualTo(400);
        LoggedRequest stripeRefund = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/refunds")).withRequestBody(WireMock.containing("payment_intent=pi_" + order))).getFirst();
        assertThat(stripeRefund.getBodyAsString()).contains("amount=5412");
        assertThat(stripeRefund.getHeader("Idempotency-Key")).startsWith(order + ":refund:");

        assertEveryJournalBalances();
        assertThat(balance(order, "2000", "org_c")).isEqualTo(10_000 - 800 - 320 - (5000 - 400));
        assertThat(balance(order, "4000", "")).isEqualTo(400);
        assertThat(balance(order, "2100", "US-TX-48201")).isEqualTo(825 - 412);
        assertThat(balance(order, "2200", "")).isZero();
        assertThat(outbox(PaymentsEvents.REFUNDED, order)).isEqualTo(1);

        JsonNode replay = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", body, "refund-" + order), 200);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("id").asString()).isEqualTo(refund.path("id").asString());
        problem(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(Map.of(
                "lineId", "L1", "itemsMinor", 5001))), "refund-too-much-" + order), 422,
                "payments.refund-exceeds-line");
    }

    /** Twelve simultaneous captures of one shipment (some retrying the same key): one capture, one journal. */
    @Test
    void concurrentCapturesOfTheSameShipmentAreIdempotent() throws Exception {
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("L1", "org_d", "Books", "SELF", 1, 4000, 0, 0)));
        String paymentId = payment.path("id").asString();
        STRIPE.capture("pi_" + order, "ch_" + order, 4000, "succeeded");
        STRIPE.chargeFees("ch_" + order, new long[] {4000, 146});
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++) {
                String key = i % 2 == 0 ? "same-key-" + order : "key-" + i + "-" + order;
                calls.add(pool.submit(() -> {
                    start.await();
                    return send("POST", "/v1/payments/" + paymentId + "/capture", Map.of("shipmentId", "S1",
                            "amountMinor", 4000), key);
                }));
            }
            start.countDown();
        }
        Set<String> captureIds = new HashSet<>();
        int created = 0;
        for (Future<HttpResponse<String>> call : calls) {
            HttpResponse<String> r = call.get();
            assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
            created += r.statusCode() == 201 ? 1 : 0;
            captureIds.add(JSON.readTree(r.body()).path("id").asString());
        }
        assertThat(created).isEqualTo(1);
        assertThat(captureIds).hasSize(1);
        assertThat(jdbc.sql("select count(*) from payment_capture where payment_id = cast(:p as uuid)")
                .param("p", paymentId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from journal_entry where order_id = :o and type = 'CAPTURE'")
                .param("o", order).query(Long.class).single()).isEqualTo(1);
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents/pi_"
                + order + "/capture")))).isNotEmpty()
                .allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo(order + ":capture:S1"));
        assertThat(balance(order, "2000", "org_d")).isEqualTo(4000 - 400 - 146);
        assertEveryJournalBalances();
    }

    @Test
    void cancelReleasesTheAuthorisationOnceAndOnlyServicesManagePayments() {
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("L1", "org_e", "Books", "SELF", 1, 3000, 0, 0)));
        String paymentId = payment.path("id").asString();
        STRIPE.cancel("pi_" + order, 3000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        assertThat(ok(send("POST", "/v1/payments/" + paymentId + "/cancel", Map.of("reason", "out of stock"),
                "cancel-" + order)).path("status").asString()).isEqualTo("CANCELED");
        assertThat(ok(send("POST", "/v1/payments/" + paymentId + "/cancel", null, "cancel-" + order))
                .path("status").asString()).isEqualTo("CANCELED");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents/pi_" + order
                + "/cancel")).withHeader("Idempotency-Key", WireMock.equalTo(order + ":cancel")));
        problem(send("POST", "/v1/payments/" + paymentId + "/capture", Map.of("shipmentId", "S1"), "c-" + order),
                409, "payments.not-capturable");
        assertThat(outbox(PaymentsEvents.INTENT_CANCELED, order)).isEqualTo(1);

        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/payments", Map.of("orderId", id("ORD"), "amountMinor", 100, "currency", "USD",
                "lines", List.of(line("L1", "org_e", "Books", "SELF", 1, 100, 0, 0))), "w"), 403,
                "auth.missing-permission");
        TestSupport.as(new Actor("ops", PrincipalType.STAFF, Optional.empty(), Set.of("ops"),
                Set.of("payments:service")));
        problem(send("POST", "/v1/payments", Map.of("orderId", id("ORD"), "amountMinor", 100, "currency", "USD",
                "lines", List.of(line("L1", "org_e", "Books", "SELF", 1, 100, 0, 0))), "x"), 403,
                "payments.service-only");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("POST", "/v1/payments", Map.of("orderId", id("ORD"), "amountMinor", 101, "currency", "USD",
                "lines", List.of(line("L1", "org_e", "Books", "SELF", 1, 100, 0, 0))), "y"), 422,
                "payments.amount-mismatch");
    }

    @Test
    void replayingTheSameOrderReturnsTheSameIntent() {
        String order = id("ORD");
        createAuthorisedPayment(order, List.of(line("L1", "org_f", "Books", "SELF", 1, 1500, 0, 0)));
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode again = ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_shopper",
                "amountMinor", 1500, "currency", "USD", "lines", List.of(line("L1", "org_f", "Books", "SELF", 1, 1500,
                        0, 0))), "another-key-" + order), 200);
        assertThat(again.path("replayed").asBoolean()).isTrue();
        problem(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 1600, "currency", "USD",
                "lines", List.of(line("L1", "org_f", "Books", "SELF", 1, 1600, 0, 0))), "third-" + order), 409,
                "payments.order-already-paid");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent")));
    }

    /** S055: only payments:configure creates versions; orders placed after the effective date use them. */
    @Test
    void feeScheduleVersionsApplyFromTheirEffectiveDate() {
        Map<String, Object> v2 = Map.of("categoryBps", Map.of("groceries", 500), "defaultCommissionBps", 900,
                "cardPctBps", 290, "cardFixedMinor", 30, "fbaPerUnitMinor", 300, "reverseCommissionOnRefund", false,
                "note", "Promo 2035", "currency", "USD");
        TestSupport.as(TestSupport.seller("org_g", "seller.owner", "payouts:request"));
        problem(send("POST", "/v1/fee-schedules", v2, null), 403, "auth.missing-permission");

        Instant effective = Instant.parse("2035-01-01T00:00:00Z");
        TestSupport.CLOCK.setNow(effective);
        TestSupport.as(TestSupport.FINANCE);
        JsonNode created = ok(send("POST", "/v1/fee-schedules", v2, null), 201);
        assertThat(created.path("version").asInt()).isGreaterThan(1);
        assertThat(created.path("createdBy").asString()).isEqualTo("fin-1");
        Map<String, Object> backdated = new HashMap<>(v2);
        backdated.put("effectiveFrom", "2034-01-01T00:00:00Z");
        problem(send("POST", "/v1/fee-schedules", backdated, null), 422, "payments.fee-schedule-backdated");
        assertThat(ok(send("GET", "/v1/fee-schedules", null, null)).size()).isGreaterThanOrEqualTo(2);

        String newOrder = id("ORD");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        STRIPE.createIntent(newOrder, "pi_" + newOrder, 10_000);
        ok(send("POST", "/v1/payments", Map.of("orderId", newOrder, "amountMinor", 10_000, "currency", "USD",
                "placedAt", "2035-02-01T00:00:00Z", "lines", List.of(line("L1", "org_g", "Groceries", "SELF", 1,
                        10_000, 0, 0))), "fs-" + newOrder), 201);
        String oldOrder = id("ORD");
        STRIPE.createIntent(oldOrder, "pi_" + oldOrder, 10_000);
        ok(send("POST", "/v1/payments", Map.of("orderId", oldOrder, "amountMinor", 10_000, "currency", "USD",
                "placedAt", "2026-02-01T00:00:00Z", "lines", List.of(line("L1", "org_g", "Groceries", "SELF", 1,
                        10_000, 0, 0))), "fs-" + oldOrder), 201);
        TestSupport.as(TestSupport.FINANCE);
        assertThat(ok(send("GET", "/v1/orders/" + newOrder + "/split", null, null)).path("lines").get(0)
                .path("commissionBps").asInt()).isEqualTo(500);
        JsonNode oldSplit = ok(send("GET", "/v1/orders/" + oldOrder + "/split", null, null));
        assertThat(oldSplit.path("feeScheduleVersion").asInt()).isEqualTo(1);
        assertThat(oldSplit.path("lines").get(0).path("commissionBps").asInt()).isEqualTo(800);
        assertThat(oldSplit.path("feeSource").asString()).isEqualTo("ESTIMATED");
    }
    @Test
    void scheduledFeeScheduleVersionsCanBeCancelledBeforeTheyTakeEffect() {
        Map<String, Object> future = Map.of("effectiveFrom", "2040-01-01T00:00:00Z", "categoryBps",
                Map.of("groceries", 100), "defaultCommissionBps", 200, "cardPctBps", 290, "cardFixedMinor", 30,
                "fbaPerUnitMinor", 300, "reverseCommissionOnRefund", true, "note", "Typo version", "currency", "USD");
        TestSupport.as(TestSupport.FINANCE);
        JsonNode created = ok(send("POST", "/v1/fee-schedules", future, null), 201);
        String id = created.path("id").asString();
        assertThat(created.path("cancelledAt").isNull()).isTrue();

        TestSupport.as(TestSupport.seller("org_g", "seller.owner", "payouts:request"));
        problem(send("POST", "/v1/fee-schedules/" + id + "/cancel", Map.of("reason", "x"), null), 403,
                "auth.missing-permission");
        TestSupport.as(TestSupport.FINANCE);
        assertThat(send("POST", "/v1/fee-schedules/" + id + "/cancel", Map.of("reason", " "), null).statusCode())
                .isEqualTo(400);
        JsonNode cancelled = ok(send("POST", "/v1/fee-schedules/" + id + "/cancel",
                Map.of("reason", "Wrong grocery rate"), null), 200);
        assertThat(cancelled.path("cancelledBy").asString()).isEqualTo("fin-1");
        assertThat(cancelled.path("cancelReason").asString()).isEqualTo("Wrong grocery rate");
        assertThat(cancelled.path("version").asInt()).isEqualTo(created.path("version").asInt());
        problem(send("POST", "/v1/fee-schedules/" + id + "/cancel", Map.of("reason", "again"), null), 409,
                "payments.fee-schedule-cancelled");
        problem(send("POST", "/v1/fee-schedules/00000000-0000-0000-0000-00000000fee1/cancel",
                Map.of("reason", "too late"), null), 409, "payments.fee-schedule-in-force");
        assertThat(send("POST", "/v1/fee-schedules/" + UUID.randomUUID() + "/cancel", Map.of("reason", "nope"), null)
                .statusCode()).isEqualTo(404);

        // Kept in the history, marked cancelled, and never in force.
        JsonNode history = ok(send("GET", "/v1/fee-schedules", null, null));
        boolean listed = false;
        for (JsonNode s : history) {
            if (s.path("id").asString().equals(id)) {
                listed = true;
                assertThat(s.path("cancelledAt").isNull()).isFalse();
            }
        }
        assertThat(listed).isTrue();
        assertThat(ok(send("GET", "/v1/fee-schedules/active?at=2041-01-01T00:00:00Z", null, null)).path("id")
                .asString()).isNotEqualTo(id);
        assertThatThrownBy(() -> jdbc.sql("update fee_schedule_cancellation set reason = 'x' where schedule_id = :id")
                .param("id", UUID.fromString(id)).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from fee_schedule where id = :id")
                .param("id", UUID.fromString(id)).update()).isInstanceOf(DataAccessException.class);
    }
}
