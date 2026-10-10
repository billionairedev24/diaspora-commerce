package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S272 pre-orders: PREORDER lines book their delivery fee (the line's shipping) to 4250 Local delivery fees, for
 * marketplace sellers and AfroTransact Retail alike, and refunds debit it there; late adds confirm the order's intent
 * off-session on the customer's default saved card or create a hosted payment link whose paid intent is attached by
 * its first webhook.
 */
class PreorderPaymentsIT extends PaymentsIntegrationTest {

    @Test
    void preorderDeliveryFeesGoTo4250AndAreRefundedFromThere() {
        String order = id("PRE");
        String seller = id("org_pre").toLowerCase(Locale.ROOT);
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("P1", seller, "Groceries", "PREORDER", 2, 2000, 639, 165),
                line("P2", RETAIL, "Groceries", "PREORDER", 1, 500, 160, 41),
                line("S1", "org_a", "Groceries", "SELF", 1, 1500, 500, 124)));
        String paymentId = payment.path("id").asString();
        long total = 2000 + 639 + 165 + 500 + 160 + 41 + 1500 + 500 + 124;
        STRIPE.chargeFees("ch_" + order, new long[] {total, 200});
        capture(paymentId, order, order + "-S1", List.of(), total, 200, true);

        assertEveryJournalBalances();
        assertThat(balance(order, "4250", "")).isEqualTo(799);
        assertThat(balance(order, "4200", "")).isZero();
        assertThat(balance(order, "4000", "")).isEqualTo(160 + 120);
        assertThat(balance(order, "4100", "")).isZero();
        JsonNode lines = ok(send("GET", "/v1/payments/" + paymentId, null, null)).path("lines");
        assertThat(lines.get(0).path("fulfilment").asString()).isEqualTo("PREORDER");

        // Cancelled before close: items, tax and the delivery fee share back (Dr 4250).
        STRIPE.refund("pi_" + order, "re_" + order, 2000 + 639 + 165, "succeeded");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("reason", "Pre-order cancelled", "lines",
                List.of(Map.of("lineId", "P1", "itemsMinor", 2000, "shippingMinor", 639, "taxMinor", 165))),
                "refund-" + order), 201);
        assertEveryJournalBalances();
        assertThat(balance(order, "4250", "")).isEqualTo(160);
        assertThat(balance(order, "4200", "")).isZero();
    }

    /** The shopper's Stripe Customer with a default saved card (as a checkout with saveForOffSession leaves it). */
    private String savedCard(String customer) {
        String cus = "cus_" + customer.replace("-", "");
        String seed = id("SEED");
        STRIPE.customer(customer, cus);
        STRIPE.createIntent(seed, "pi_" + seed, 1000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments", Map.of("orderId", seed, "amountMinor", 1000, "currency", "USD",
                "shopperId", customer, "saveForOffSession", true, "lines",
                List.of(line("L1", "org_a", "Groceries", "SELF", 1, 1000, 0, 0))), "create-" + seed), 201);
        webhook(event(id("evt"), "payment_method.attached", "{\"id\":\"pm_card_" + customer + "\",\"object\":"
                + "\"payment_method\",\"type\":\"card\",\"customer\":\"" + cus + "\",\"card\":{\"brand\":"
                + "\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}}"));
        return cus;
    }

    @Test
    void lateAddsChargeTheDefaultSavedCardOffSession() {
        String customer = id("cust").toLowerCase(Locale.ROOT);
        String cus = savedCard(customer);
        String order = id("LATE");
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(StripeStubs.intent("pi_" + order, 2399, "requires_capture", null, 0))));
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode created = ok(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 2399,
                "currency", "USD", "shopperId", customer, "offSession", true, "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 2, 1600, 799, 0))), "create-" + order), 201);
        assertThat(created.path("status").asString()).isEqualTo("AUTHORIZED");
        LoggedRequest stripe = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents")).withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent")))
                .getFirst();
        assertThat(stripe.getBodyAsString()).contains("off_session=true", "confirm=true", "customer=" + cus,
                "payment_method=pm_card_" + customer, "capture_method=manual");

        // Without a saved card the request is refused.
        problem(send("POST", "/v1/payments", Map.of("orderId", id("LATE"), "amountMinor", 100, "currency", "USD",
                "shopperId", id("nobody"), "offSession", true, "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 1, 100, 0, 0))), "create-x-" + order), 422,
                "payments.no-saved-method");
    }

    @Test
    void lateAddsPayThroughAHostedLinkWhoseIntentIsAttachedByItsWebhook() {
        String order = id("LINK");
        String session = "cs_test_" + order;
        WIREMOCK.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/checkout/sessions"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":payment-link"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + session + "\",\"object\":\"checkout.session\",\"url\":"
                                + "\"https://checkout.stripe.com/c/pay/" + session + "\",\"expires_at\":"
                                + (TestSupport.CLOCK.instant().getEpochSecond() + 12 * 3600) + "}")));
        Map<String, Object> body = new HashMap<>(Map.of("orderId", order, "amountMinor", 1799, "currency", "USD",
                "shopperId", "cust-link", "paymentLinkHours", 12, "paymentMethodTypes", List.of("card"), "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 1, 1000, 799, 0))));
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode created = ok(send("POST", "/v1/payments", body, "create-" + order), 201);
        String paymentId = created.path("id").asString();
        assertThat(created.path("paymentLinkUrl").asString()).isEqualTo("https://checkout.stripe.com/c/pay/"
                + session);
        assertThat(created.path("paymentLinkExpiresAt").isNull()).isFalse();
        assertThat(created.path("status").asString()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(created.path("stripePaymentIntentId").isNull()).isTrue();
        String sent = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/checkout/sessions")))
                .getLast().getBodyAsString();
        assertThat(sent).contains("mode=payment", "payment_intent_data[capture_method]=manual",
                "payment_intent_data[metadata][payment_id]=" + paymentId, "line_items[0][price_data][unit_amount]=1799");

        // The shopper pays on the hosted page: Stripe's intent names our payment in its metadata.
        String pi = "pi_link_" + order;
        String intent = StripeStubs.intent(pi, 1799, "requires_capture", null, 0)
                .replace("}", ",\"metadata\":{\"payment_id\":\"" + paymentId + "\",\"order_id\":\"" + order + "\"}}");
        webhook(event(id("evt"), "payment_intent.amount_capturable_updated", intent));
        JsonNode paid = ok(send("GET", "/v1/payments/" + paymentId, null, null));
        assertThat(paid.path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(paid.path("stripePaymentIntentId").asString()).isEqualTo(pi);
        assertThat(outbox("payments.intent.succeeded", order)).isEqualTo(1);
    }
}
