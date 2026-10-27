package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S320 against PostgreSQL and the Stripe stand-in: the credit ledger (grant, spend, refund, adjustment), spending at
 * checkout (credit first, the card for the rest, no card when credit pays everything), capture and refund paths with
 * credit, and the guarantees: a retried order never spends twice, parallel orders never overspend, and credit never
 * becomes cash.
 */
class CreditLedgerIT extends PaymentsIntegrationTest {

    private static final Actor PROGRAM = new Actor("svc-referral", PrincipalType.SERVICE, Optional.empty(), Set.of(),
            Set.of("payments:credit.grant"));
    private static final Actor MAKER = finance("fin-maker");
    private static final Actor CHECKER = finance("fin-checker");

    private static Actor finance(String subject) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.finance"),
                Set.of("payments:read", "ledger:adjust", "ledger:approve"));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode grant(String customer, long amount, String source, String key) {
        TestSupport.as(PROGRAM);
        return ok(send("POST", "/v1/credit/grants", Map.of("customerId", customer, "amountMinor", amount,
                "source", source, "reason", "Referral reward for a friend's first order"), key), 201);
    }

    private long balance(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/credit/me", null, null)).path("balanceMinor").asLong();
    }

    private JsonNode entries(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/credit/me/entries?limit=100", null, null));
    }

    private HttpResponse<String> createOrder(String order, String shopper, long amount, boolean useCredit) {
        STRIPE.createIntent(order, "pi_" + order, amount);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_x", "amountMinor", amount,
                "currency", "USD", "shopperId", shopper, "useCredit", useCredit,
                "lines", List.of(line("L1", "org_credit", "Books", "SELF", 1, amount, 0, 0))), "create-" + order);
    }

    /** Authorises the card part at Stripe and reads it, as the shopper's confirmation does. */
    private JsonNode authorise(JsonNode payment, String order) {
        long card = payment.path("amountMinor").asLong() - payment.path("creditMinor").asLong();
        STRIPE.retrieveIntent("pi_" + order, card, "requires_capture");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("GET", "/v1/payments/" + payment.path("id").asString() + "?refresh=true", null, null));
    }

    private JsonNode captureOrder(String paymentId, String order, long amount, long cardShare, long fee) {
        STRIPE.chargeFees("ch_" + order, new long[] {cardShare, fee});
        STRIPE.capture("pi_" + order, "ch_" + order, cardShare, "succeeded");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("POST", "/v1/payments/" + paymentId + "/capture", Map.of("shipmentId", order + "-S1",
                "lineIds", List.of(), "amountMinor", amount, "finalCapture", true), "cap-" + order), 201);
    }

    private static boolean absent(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    private long creditEntries(String customer) {
        return jdbc.sql("select coalesce(sum(amount_minor), 0) from credit_entry where customer_id = :c")
                .param("c", customer).query(Long.class).single();
    }

    // ------------------------------------------------------------------ the ledger

    @Test
    void aGrantIsAnIdempotentEntryWithReasonActorAndSource() {
        String customer = id("cust");
        JsonNode first = grant(customer, 1500, "REFERRAL", "ref-" + customer);
        assertThat(first.path("kind").asString()).isEqualTo("GRANT");
        assertThat(first.path("source").asString()).isEqualTo("REFERRAL");
        assertThat(first.path("actor").asString()).isEqualTo("svc-referral");
        assertThat(first.path("reason").asString()).isNotBlank();
        assertThat(first.path("balanceAfterMinor").asLong()).isEqualTo(1500);

        JsonNode replay = grant(customer, 1500, "REFERRAL", "ref-" + customer);
        assertThat(replay.path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(balance(customer)).isEqualTo(1500);
        assertThat(entries(customer)).hasSize(1);

        TestSupport.as(PROGRAM);
        problem(send("POST", "/v1/credit/grants", Map.of("customerId", customer, "amountMinor", 900, "source",
                "REFERRAL", "reason", "Another amount under the same key"), "ref-" + customer), 409,
                "payments.credit-key-reused");

        // referral, membership and promotions are the only sources a program may grant from
        problem(send("POST", "/v1/credit/grants", Map.of("customerId", customer, "amountMinor", 900, "source",
                "STAFF", "reason", "Programs cannot pose as staff"), "x-" + customer), 422, "payments.credit-source");
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/credit/grants", Map.of("customerId", customer, "amountMinor", 900, "source",
                "PROMO", "reason", "A shopper cannot grant credit"), "y-" + customer), 403, "payments.service-only");
        assertThat(creditEntries(customer)).isEqualTo(balance(customer));
        assertEveryJournalBalances();
    }

    @Test
    void theLedgerIsAppendOnlyAndHasNoWayOutToCash() {
        String customer = id("cust");
        grant(customer, 1000, "PROMO", "promo-" + customer);
        assertThatThrownBy(() -> jdbc.sql("update credit_entry set amount_minor = 9999999 where customer_id = :c")
                .param("c", customer).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from credit_entry where customer_id = :c").param("c", customer)
                .update()).hasMessageContaining("append-only");
        // a balance below zero is refused by the database itself
        assertThatThrownBy(() -> jdbc.sql("update credit_account set balance_minor = -1 where customer_id = :c")
                .param("c", customer).update()).hasMessageContaining("balance_minor");

        // nothing in the API moves credit out: no operation mentions cash, payouts, withdrawals or transfers
        JsonNode api = ok(send("GET", "/v3/api-docs", null, null));
        List<String> creditPaths = new ArrayList<>();
        api.path("paths").propertyNames().forEach(p -> {
            if (p.startsWith("/v1/credit") || p.startsWith("/v1/gift-cards")) {
                creditPaths.add(p);
            }
        });
        assertThat(creditPaths).isNotEmpty().noneMatch(p -> p.matches(".*(cash|payout|withdraw|transfer|convert).*"));
        String text = api.toString().toLowerCase();
        assertThat(creditPaths.stream().map(p -> api.path("paths").path(p).toString().toLowerCase()))
                .noneMatch(t -> t.contains("cash out") || t.contains("withdraw"));
        assertThat(text).contains("never goes back to a card");
    }

    // ------------------------------------------------------------------ spending at checkout

    @Test
    void creditPaysFirstAndTheCardPaysTheRest() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 2000, "PROMO", "promo-" + customer);

        JsonNode payment = ok(createOrder(order, customer, 5000, true), 201);
        assertThat(payment.path("creditMinor").asLong()).isEqualTo(2000);
        assertThat(payment.path("status").asString()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(payment.path("clientSecret").asString()).isNotBlank();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent"))).getFirst()
                .getBodyAsString()).contains("amount=3000");
        assertThat(balance(customer)).isZero();
        JsonNode spend = entries(customer).get(0);
        assertThat(spend.path("kind").asString()).isEqualTo("SPEND");
        assertThat(spend.path("amountMinor").asLong()).isEqualTo(-2000);
        assertThat(spend.path("orderId").asString()).isEqualTo(order);
        assertThat(spend.path("source").asString()).isEqualTo("ORDER");

        // the retried checkout (same order, same key) spends nothing more
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        grant(customer, 700, "PROMO", "promo2-" + customer);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode replay = ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_x",
                "amountMinor", 5000, "currency", "USD", "shopperId", customer, "useCredit", true, "lines",
                List.of(line("L1", "org_credit", "Books", "SELF", 1, 5000, 0, 0))), "create-" + order), 200);
        assertThat(replay.path("id").asString()).isEqualTo(payment.path("id").asString());
        assertThat(balance(customer)).isEqualTo(700);
        assertThat(creditEntries(customer)).isEqualTo(700);
    }

    @Test
    void creditThatPaysEverythingNeedsNoCard() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 8000, "PROMO", "promo-" + customer);

        JsonNode payment = ok(createOrder(order, customer, 5000, true), 201);
        assertThat(payment.path("creditMinor").asLong()).isEqualTo(5000);
        assertThat(payment.path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(absent(payment.path("stripePaymentIntentId"))).isTrue();
        assertThat(absent(payment.path("clientSecret"))).isTrue();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent")))).isEmpty();
        assertThat(balance(customer)).isEqualTo(3000);
        assertThat(outbox("payments.intent.succeeded", order)).isEqualTo(1);
    }

    @Test
    void creditLeavesAtLeastFiftyCentsForTheCardOrPaysAll() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 4980, "PROMO", "promo-" + customer);
        JsonNode payment = ok(createOrder(order, customer, 5000, true), 201);
        // paying 49.80 would leave 0.20, which Stripe cannot charge: credit pays 49.50 and the card 0.50
        assertThat(payment.path("creditMinor").asLong()).isEqualTo(4950);
        assertThat(balance(customer)).isEqualTo(30);
    }

    @Test
    void withoutUseCreditTheCardPaysEverythingAndNothingIsSpent() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 2000, "PROMO", "promo-" + customer);
        JsonNode payment = ok(createOrder(order, customer, 5000, false), 201);
        assertThat(payment.path("creditMinor").asLong()).isZero();
        assertThat(balance(customer)).isEqualTo(2000);
    }

    @Test
    void parallelOrdersNeverSpendMoreThanTheBalance() throws Exception {
        String customer = id("cust");
        grant(customer, 5000, "PROMO", "promo-" + customer);
        int orders = 8;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String order = id("ORD");
            ids.add(order);
            STRIPE.createIntent(order, "pi_" + order, 1000);
        }
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ExecutorService pool = Executors.newFixedThreadPool(orders);
        try {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (String order : ids) {
                calls.add(pool.submit(() -> send("POST", "/v1/payments", Map.of("orderId", order, "customerId",
                        "cus_x", "amountMinor", 1000, "currency", "USD", "shopperId", customer, "useCredit", true,
                        "lines", List.of(line("L1", "org_credit", "Books", "SELF", 1, 1000, 0, 0))),
                        "create-" + order)));
            }
            for (Future<HttpResponse<String>> call : calls) {
                assertThat(call.get().statusCode()).as(call.get().body()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        long credited = 0;
        for (String order : ids) {
            credited += jdbc.sql("select credit_minor from payment where order_id = :o").param("o", order)
                    .query(Long.class).single();
        }
        // five orders of 10.00 take the 50.00; the other three are paid by card only
        assertThat(credited).isEqualTo(5000);
        assertThat(balance(customer)).isZero();
        assertThat(creditEntries(customer)).isZero();
        assertThat(jdbc.sql("select count(*) from credit_entry where customer_id = :c and kind = 'SPEND'")
                .param("c", customer).query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select min(balance_after_minor) from credit_entry where customer_id = :c")
                .param("c", customer).query(Long.class).single()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ capture, cancel, refund

    @Test
    void aCaptureChargesTheCardOnlyForItsShareAndBooksTheCreditAsRevenue() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 2000, "PROMO", "promo-" + customer);
        JsonNode payment = authorise(ok(createOrder(order, customer, 5000, true), 201), order);
        assertThat(payment.path("status").asString()).isEqualTo("AUTHORIZED");

        JsonNode capture = captureOrder(payment.path("id").asString(), order, 5000, 3000, 117);
        assertThat(capture.path("amountMinor").asLong()).isEqualTo(5000);
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents/pi_" + order + "/capture"))).getFirst().getBodyAsString())
                .contains("amount_to_capture=3000");
        assertEveryJournalBalances();
        assertThat(-balance(order, "1000", "")).isEqualTo(3000 - 117);
        assertThat(-balance(order, "2600", "")).isEqualTo(2000);
        // the seller is paid for the whole order whatever paid it: items less commission less the Stripe fee share
        assertThat(balance(order, "2000", "org_credit")).isEqualTo(5000 - 500 - 117);
    }

    @Test
    void aCreditOnlyOrderCapturesWithoutTouchingStripe() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 9000, "PROMO", "promo-" + customer);
        JsonNode payment = ok(createOrder(order, customer, 5000, true), 201);

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode capture = ok(send("POST", "/v1/payments/" + payment.path("id").asString() + "/capture",
                Map.of("shipmentId", order + "-S1", "lineIds", List.of(), "amountMinor", 5000, "finalCapture", true),
                "cap-" + order), 201);
        assertThat(capture.path("paymentStatus").asString()).isEqualTo("CAPTURED");
        assertThat(capture.path("stripeFeeMinor").asLong()).isZero();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathMatching(
                "/v1/payment_intents/.*" + order + ".*")))).isEmpty();
        assertEveryJournalBalances();
        assertThat(balance(order, "1000", "")).isZero();
        assertThat(-balance(order, "2600", "")).isEqualTo(5000);
    }

    @Test
    void cancellingAnUnpaidOrderGivesTheCreditBackOnce() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 2000, "PROMO", "promo-" + customer);
        JsonNode payment = ok(createOrder(order, customer, 5000, true), 201);
        assertThat(balance(customer)).isZero();

        STRIPE.cancel("pi_" + order, 3000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments/" + payment.path("id").asString() + "/cancel", Map.of("reason", "expired"),
                "cancel-" + order));
        assertThat(balance(customer)).isEqualTo(2000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments/" + payment.path("id").asString() + "/cancel", Map.of("reason", "expired"),
                "cancel2-" + order));
        assertThat(balance(customer)).isEqualTo(2000);
        JsonNode back = entries(customer).get(0);
        assertThat(back.path("kind").asString()).isEqualTo("REFUND");
        assertThat(back.path("orderId").asString()).isEqualTo(order);
        // never revenue, so nothing was booked in the general ledger for it
        assertThat(balance(order, "2600", "")).isZero();
    }

    @Test
    void aRefundGoesBackToWhatPaidEachPart() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 2000, "PROMO", "promo-" + customer);
        JsonNode payment = authorise(ok(createOrder(order, customer, 5000, true), 201), order);
        String paymentId = payment.path("id").asString();
        captureOrder(paymentId, order, 5000, 3000, 117);

        // refund half of the order: 25.00 = 10.00 back as credit, 15.00 back to the card
        STRIPE.refund("pi_" + order, "re_" + order, 1500, "succeeded");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(Map.of(
                "lineId", "L1", "itemsMinor", 2500)), "reason", "Return of half the order"), "refund-" + order), 201);
        assertThat(refund.path("amountMinor").asLong()).isEqualTo(2500);
        assertThat(refund.path("creditMinor").asLong()).isEqualTo(1000);
        assertThat(refund.path("cardMinor").asLong()).isEqualTo(1500);
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=pi_" + order))).getFirst().getBodyAsString())
                .contains("amount=1500");
        assertThat(balance(customer)).isEqualTo(1000);
        assertThat(entries(customer).get(0).path("kind").asString()).isEqualTo("REFUND");
        assertEveryJournalBalances();
        assertThat(balance(order, "2200", "")).isZero();

        // the rest, with the card part taken as credit by the shopper's choice: one way, no card refund
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode rest = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(Map.of(
                "lineId", "L1", "itemsMinor", 2500)), "reason", "The rest was returned", "cardAsCredit", true,
                "chosenBy", customer), "refund2-" + order), 201);
        assertThat(rest.path("creditMinor").asLong()).isEqualTo(2500);
        assertThat(rest.path("cardMinor").asLong()).isZero();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=pi_" + order)))).hasSize(1);
        assertThat(balance(customer)).isEqualTo(1000 + 2500);
        JsonNode taken = entries(customer).get(0);
        assertThat(taken.path("reason").asString()).contains("taken as credit");
        assertThat(absent(taken.path("actor"))).as("the shopper's view hides actors").isTrue();
        assertEveryJournalBalances();
        // 2600 holds what the shopper has back, 2200 owes nothing: the credit refund never reached Stripe
        assertThat(balance(order, "2200", "")).isZero();
        assertThat(creditEntries(customer)).isEqualTo(3500);
    }

    @Test
    void creditIsNeverRefundedToTheCardEvenWhenTheOrderIsRefundedInFull() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 9000, "PROMO", "promo-" + customer);
        JsonNode payment = ok(createOrder(order, customer, 5000, true), 201);
        String paymentId = payment.path("id").asString();
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments/" + paymentId + "/capture", Map.of("shipmentId", order + "-S1", "lineIds",
                List.of(), "amountMinor", 5000, "finalCapture", true), "cap-" + order), 201);

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        // asking for the "card part as credit" when there is no card part changes nothing: all of it is credit
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(Map.of(
                "lineId", "L1", "itemsMinor", 5000)), "reason", "Order returned in full"), "refund-" + order), 201);
        assertThat(refund.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(refund.path("creditMinor").asLong()).isEqualTo(5000);
        assertThat(refund.path("cardMinor").asLong()).isZero();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=pi_" + order)))).isEmpty();
        assertThat(balance(customer)).isEqualTo(9000);
        assertEveryJournalBalances();
    }

    @Test
    void theShopperOrStaffCanChooseCreditForTheCardPartOfAnOrdersRefundsAndItIsOneWay() {
        String customer = id("cust");
        String order = id("ORD");
        grant(customer, 2000, "PROMO", "promo-" + customer);
        JsonNode payment = authorise(ok(createOrder(order, customer, 5000, true), 201), order);
        String paymentId = payment.path("id").asString();
        captureOrder(paymentId, order, 5000, 3000, 117);

        // another shopper cannot choose for this order, a missing reason stops staff
        TestSupport.as(TestSupport.customer(id("other")));
        problem(send("POST", "/v1/customers/me/orders/" + order + "/credit-refunds", null, null), 403,
                "payments.not-your-order");
        TestSupport.as(MAKER);
        problem(send("POST", "/v1/orders/" + order + "/credit-refunds", Map.of("reason", "short"), null), 422,
                "payments.reason-required");
        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/orders/" + order + "/credit-refunds", Map.of("reason",
                "Finance without the adjust permission"), null), 403, "auth.missing-permission");

        TestSupport.as(TestSupport.customer(customer));
        JsonNode choice = ok(send("POST", "/v1/customers/me/orders/" + order + "/credit-refunds", null, null));
        assertThat(choice.path("cardRefundsAsCredit").asBoolean()).isTrue();
        assertThat(choice.path("cardPartMinor").asLong()).isEqualTo(3000);
        assertThat(choice.path("chosenBy").asString()).isEqualTo(customer);
        TestSupport.as(MAKER);
        ok(send("POST", "/v1/orders/" + order + "/credit-refunds", Map.of("reason",
                "The customer asked us to refund to credit"), null));
        assertThat(jdbc.sql("select card_as_credit_by from payment where order_id = :o").param("o", order)
                .query(String.class).single()).isEqualTo(customer);

        // a refund made later by anyone (a cancelled shipment, a claim) now returns everything as credit
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(Map.of(
                "lineId", "L1", "itemsMinor", 2500)), "reason", "Half the order was returned"), "refund-" + order),
                201);
        assertThat(refund.path("creditMinor").asLong()).isEqualTo(2500);
        assertThat(refund.path("cardMinor").asLong()).isZero();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=pi_" + order)))).isEmpty();
        assertThat(balance(customer)).isEqualTo(2500);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        assertThat(ok(send("GET", "/v1/payments/" + paymentId, null, null)).path("cardRefundsAsCredit")
                .asBoolean()).isTrue();
        assertEveryJournalBalances();

        // an order paid with credit only has no card part to choose about
        String creditOnly = id("ORD");
        grant(customer, 9000, "PROMO", "promo2-" + customer);
        ok(createOrder(creditOnly, customer, 4000, true), 201);
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/customers/me/orders/" + creditOnly + "/credit-refunds", null, null), 409,
                "payments.no-card-portion");
    }

    // ------------------------------------------------------------------ staff grants and the four-eyes limit

    @Test
    void staffGrantWithinTheLimitPostsAtOnceAndAboveItNeedsAnotherApprover() {
        String customer = id("cust");
        TestSupport.as(MAKER);
        JsonNode small = ok(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor", 2500,
                "source", "STAFF", "reason", "Goodwill for a late delivery of order 1001"), null), 201);
        assertThat(small.path("status").asString()).isEqualTo("POSTED");
        assertThat(balance(customer)).isEqualTo(2500);

        TestSupport.as(MAKER);
        JsonNode big = ok(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor",
                25_000, "source", "STAFF", "reason", "Compensation agreed with the customer after a lost parcel"),
                null), 201);
        assertThat(big.path("status").asString()).isEqualTo("PROPOSED");
        assertThat(balance(customer)).isEqualTo(2500);

        TestSupport.as(MAKER);
        problem(send("POST", "/v1/credit/adjustments/" + big.path("id").asString() + "/approve", Map.of(), null), 403,
                "payments.adjustment-four-eyes");
        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/credit/adjustments/" + big.path("id").asString() + "/approve", Map.of(), null), 403,
                "auth.missing-permission");
        TestSupport.as(CHECKER);
        JsonNode posted = ok(send("POST", "/v1/credit/adjustments/" + big.path("id").asString() + "/approve",
                Map.of("note", "Checked the claim"), null));
        assertThat(posted.path("status").asString()).isEqualTo("POSTED");
        assertThat(posted.path("decidedBy").asString()).isEqualTo("fin-checker");
        assertThat(balance(customer)).isEqualTo(27_500);
        assertThat(outbox("payments.credit.adjusted", customer)).as("one audit event per posted adjustment")
                .isEqualTo(2);
        TestSupport.as(CHECKER);
        problem(send("POST", "/v1/credit/adjustments/" + big.path("id").asString() + "/approve", Map.of(), null), 422,
                "payments.adjustment-decided");

        // rejecting needs a reason and leaves the balance alone
        TestSupport.as(MAKER);
        JsonNode other = ok(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor",
                30_000, "source", "PROMO", "reason", "Spring promotion for loyal customers"), null), 201);
        TestSupport.as(CHECKER);
        problem(send("POST", "/v1/credit/adjustments/" + other.path("id").asString() + "/reject", Map.of(), null),
                422, "payments.reason-required");
        JsonNode rejected = ok(send("POST", "/v1/credit/adjustments/" + other.path("id").asString() + "/reject",
                Map.of("note", "Not within the promotion budget"), null));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(balance(customer)).isEqualTo(27_500);
        assertEveryJournalBalances();
    }

    @Test
    void staffMustGiveAReasonAndCannotTakeMoreThanTheBalance() {
        String customer = id("cust");
        TestSupport.as(MAKER);
        problem(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor", 500, "source",
                "STAFF", "reason", "short"), null), 422, "payments.reason-required");
        ok(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor", 500, "source",
                "STAFF", "reason", "Goodwill for a damaged parcel"), null), 201);
        TestSupport.as(MAKER);
        problem(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor", -900, "source",
                "STAFF", "reason", "Correcting an entry made twice by mistake"), null), 422,
                "payments.credit-insufficient");
        JsonNode taken = ok(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor",
                -200, "source", "STAFF", "reason", "Correcting an entry made twice by mistake"), null), 201);
        assertThat(taken.path("status").asString()).isEqualTo("POSTED");
        assertThat(balance(customer)).isEqualTo(300);
        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/credit/adjustments", Map.of("customerId", customer, "amountMinor", 500, "source",
                "STAFF", "reason", "Finance without the adjust permission"), null), 403, "auth.missing-permission");
        assertEveryJournalBalances();
    }

    @Test
    void financeSeesCreditAndGiftCardLiabilitiesApart() {
        String customer = id("cust");
        grant(customer, 1200, "REFERRAL", "ref-" + customer);
        TestSupport.as(TestSupport.FINANCE);
        JsonNode report = ok(send("GET", "/v1/credit/liabilities", null, null));
        assertThat(report.path("creditOutstandingMinor").asLong()).isGreaterThanOrEqualTo(1200);
        assertThat(report.path("flowsMinor").path("GRANT/REFERRAL").asLong()).isGreaterThanOrEqualTo(1200);
        assertThat(report.has("giftCardsOutstandingMinor")).isTrue();
        assertThat(report.has("ledgerCreditMinor")).isTrue();
        TestSupport.as(TestSupport.customer(customer));
        problem(send("GET", "/v1/credit/liabilities", null, null), 403, "auth.missing-permission");
    }
}
