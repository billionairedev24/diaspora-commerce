package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

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
 * off-session on the customer's default saved card, or get an ordinary manual-capture PaymentIntent that the customer
 * confirms on AfroTransact's pay-now page (never a Stripe-hosted Checkout Session).
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

        // A replay (orders retrying after a timeout) returns the same payment and charges the card once.
        JsonNode replayed = ok(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 2399,
                "currency", "USD", "shopperId", customer, "offSession", true, "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 2, 1600, 799, 0))), "create-" + order), 200);
        assertThat(replayed.path("id").asString()).isEqualTo(created.path("id").asString());
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent")));

        // Without a saved card the request is refused.
        problem(send("POST", "/v1/payments", Map.of("orderId", id("LATE"), "amountMinor", 100, "currency", "USD",
                "shopperId", id("nobody"), "offSession", true, "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 1, 100, 0, 0))), "create-x-" + order), 422,
                "payments.no-saved-method");
    }

    @Test
    void payNowLateAddsGetAPaymentIntentNeverACheckoutSession() {
        String order = id("LINK");
        String pi = "pi_" + order;
        STRIPE.createIntent(order, pi, 1799);
        Map<String, Object> body = Map.of("orderId", order, "amountMinor", 1799, "currency", "USD",
                "shopperId", "cust-link", "metadata", Map.of("channel", "AGENT_LATE"), "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 1, 1000, 799, 0)));
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode created = ok(send("POST", "/v1/payments", body, "create-" + order), 201);

        assertThat(created.path("stripePaymentIntentId").asString()).isEqualTo(pi);
        assertThat(created.path("clientSecret").asString()).isNotBlank();
        assertThat(created.has("paymentLinkUrl")).isFalse();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":create-intent"))
                .withRequestBody(WireMock.containing("capture_method=manual")));
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/checkout/.*")));
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payment_links.*")));
    }

    @Test
    void cancellingAnUnpaidPayNowLateAddCancelsItsPaymentIntent() {
        String order = id("LINKX");
        String pi = "pi_" + order;
        STRIPE.createIntent(order, pi, 1799);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        String paymentId = ok(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 1799,
                "currency", "USD", "shopperId", "cust-link", "lines",
                List.of(line("P1", "org_a", "Groceries", "PREORDER", 1, 1000, 799, 0))), "create-" + order), 201)
                .path("id").asString();
        STRIPE.cancel(pi, 1799);

        // orders' expiry job: the pay-now link expired unpaid.
        JsonNode cancelled = ok(send("POST", "/v1/payments/" + paymentId + "/cancel", Map.of("reason",
                "Late pre-order add was not paid in time"), "cancel-" + order), 200);

        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELED");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents/" + pi + "/cancel"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":cancel")));
    }
}
