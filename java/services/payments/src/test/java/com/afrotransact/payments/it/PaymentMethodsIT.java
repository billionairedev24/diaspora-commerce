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
 * Saved customer payment methods: lazy Stripe Customer, SetupIntents, the list read from Stripe (webhooks only keep
 * the local copy current), default rules.
 */
class PaymentMethodsIT extends PaymentsIntegrationTest {

    /** The wallet's methods ({@code {stripeCustomerId, items}}). */
    private JsonNode items() {
        JsonNode wallet = ok(send("GET", "/v1/customers/me/payment-methods", null, null));
        assertThat(wallet.has("stripeCustomerId")).isTrue();
        return wallet.path("items");
    }

    private static String card(String pmId, String customer, String brand, String last4) {
        return "{\"id\":\"" + pmId + "\",\"object\":\"payment_method\",\"type\":\"card\",\"customer\":"
                + (customer == null ? "null" : "\"" + customer + "\"") + ",\"card\":{\"brand\":\"" + brand
                + "\",\"last4\":\"" + last4 + "\",\"exp_month\":12,\"exp_year\":2030}}";
    }

    private static String bank(String pmId, String customer) {
        return "{\"id\":\"" + pmId + "\",\"object\":\"payment_method\",\"type\":\"us_bank_account\",\"customer\":\""
                + customer + "\",\"us_bank_account\":{\"bank_name\":\"STRIPE TEST BANK\",\"last4\":\"6789\","
                + "\"account_type\":\"checking\"}}";
    }

    @Test
    void savedMethodsLifecycle() {
        String subject = id("cust").toLowerCase(Locale.ROOT);
        String cus = "cus_" + subject.replace("-", "");
        TestSupport.as(TestSupport.customer(subject));
        assertThat(items().size()).isZero();

        STRIPE.customer(subject, cus);
        // Stripe is unreachable for this customer's list in this test: the wallet is the local copy, kept by webhooks.
        STRIPE.customerWalletDown(cus);
        STRIPE.setupIntent(cus, "seti_" + subject);
        JsonNode si = ok(send("POST", "/v1/customers/me/setup-intents", Map.of("types", List.of("card",
                "us_bank_account")), "si-1-" + subject), 201);
        assertThat(si.path("clientSecret").asString()).isEqualTo("seti_" + subject + "_secret_abc");
        ok(send("POST", "/v1/customers/me/setup-intents", null, "si-2-" + subject), 201);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/customers"))
                .withRequestBody(WireMock.containing(subject)));
        LoggedRequest setup = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/setup_intents"))
                .withRequestBody(WireMock.containing("customer=" + cus))).getFirst();
        assertThat(setup.getBodyAsString()).contains("usage=off_session", "allowed_payment_method_types[0]=card",
                "allowed_payment_method_types[1]=us_bank_account", "[verification_method]=automatic");
        problem(send("POST", "/v1/customers/me/setup-intents", Map.of("types", List.of("bitcoin")), null), 422,
                "payments.invalid-method-type");

        String visa = "pm_visa_" + subject;
        webhook(event(id("evt"), "payment_method.attached", card(visa, cus, "visa", "4242")));
        JsonNode list = items();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("type").asString()).isEqualTo("card");
        assertThat(list.get(0).path("last4").asString()).isEqualTo("4242");
        assertThat(list.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(list.toString()).doesNotContain("4242424242424242");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/customers/" + cus))
                .withRequestBody(WireMock.containing("[default_payment_method]=" + visa)));

        String ach = "pm_ach_" + subject;
        STRIPE.paymentMethod(bank(ach, cus), ach);
        webhook(event(id("evt"), "setup_intent.requires_action", "{\"id\":\"seti_" + subject + "\",\"object\":"
                + "\"setup_intent\",\"status\":\"requires_action\",\"customer\":\"" + cus + "\",\"payment_method\":\""
                + ach + "\",\"next_action\":{\"type\":\"verify_with_microdeposits\"}}"));
        JsonNode pending = items().get(1);
        assertThat(pending.path("type").asString()).isEqualTo("us_bank_account");
        assertThat(pending.path("verified").asBoolean()).isFalse();
        assertThat(pending.path("bankName").asString()).isEqualTo("STRIPE TEST BANK");
        problem(send("POST", "/v1/customers/me/payment-methods/" + ach + "/default", null, null), 409,
                "payments.method-unverified");
        webhook(event(id("evt"), "setup_intent.succeeded", "{\"id\":\"seti_" + subject + "\",\"object\":"
                + "\"setup_intent\",\"status\":\"succeeded\",\"customer\":\"" + cus + "\",\"payment_method\":\""
                + ach + "\"}"));
        assertThat(items().get(1).path("verified").asBoolean()).isTrue();

        problem(send("DELETE", "/v1/customers/me/payment-methods/" + visa, null, null), 409,
                "payments.default-method-required");
        JsonNode def = ok(send("POST", "/v1/customers/me/payment-methods/" + ach + "/default", null, null));
        assertThat(def.path("isDefault").asBoolean()).isTrue();
        STRIPE.paymentMethod(card(visa, null, "visa", "4242"), visa);
        assertThat(send("DELETE", "/v1/customers/me/payment-methods/" + visa, null, null).statusCode())
                .isEqualTo(204);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_methods/" + visa
                + "/detach")).withHeader("Idempotency-Key", WireMock.equalTo("detach:" + visa)));
        list = items();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("id").asString()).isEqualTo(ach);

        // Another shopper cannot touch this wallet.
        TestSupport.as(TestSupport.customer("someone-else-" + subject));
        problem(send("POST", "/v1/customers/me/payment-methods/" + ach + "/default", null, null), 404,
                "payments.payment-method-not-found");
        // Detached in the Stripe Dashboard: the list follows.
        TestSupport.as(TestSupport.customer(subject));
        webhook(event(id("evt"), "payment_method.detached", bank(ach, cus).replace("\"customer\":\"" + cus + "\"",
                "\"customer\":null")));
        assertThat(items().size()).isZero();
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("GET", "/v1/customers/me/payment-methods", null, null), 403, "payments.customer-only");

        // S245: checkout passes the shopper; the PaymentIntent carries their Stripe Customer so the mobile
        // PaymentSheet (ephemeral key for that Customer) lists the saved cards. No Customer is created for it.
        String order = id("ORD");
        STRIPE.createIntent(order, "pi_" + order, 2124);
        ok(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 2124, "currency", "USD",
                "shopperId", subject, "lines", List.of(line("L1", "org_a", "Groceries", "SELF", 1, 1500, 500, 124))),
                "create-" + order), 201);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing(order)).withRequestBody(WireMock.containing("customer=" + cus)));
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/customers"))
                .withRequestBody(WireMock.containing(subject)));
    }

    private static String walletCard(String pmId, String customer, String brand, String last4, String wallet) {
        return card(pmId, customer, brand, last4).replace("\"exp_year\":2030}", "\"exp_year\":2030,\"wallet\":"
                + (wallet == null ? "null" : "{\"type\":\"" + wallet + "\"}") + "}");
    }

    private static String typed(String pmId, String customer, String type) {
        return "{\"id\":\"" + pmId + "\",\"object\":\"payment_method\",\"type\":\"" + type + "\",\"customer\":\""
                + customer + "\"}";
    }

    /**
     * Regression: a method saved while paying (setup_future_usage) or on Your Payments is listed straight away, read
     * from the shopper's Stripe Customer, with no {@code payment_method.attached} webhook ever delivered (no
     * {@code stripe listen} locally). Wallet types are kept, Stripe's default is marked, and the list follows
     * Stripe when a method is detached or the default changes in the Dashboard.
     */
    @Test
    void aMethodSavedAtCheckoutIsListedFromStripeWithoutItsWebhook() {
        String subject = id("shopper").toLowerCase(Locale.ROOT);
        String cus = "cus_" + subject.replace("-", "");
        STRIPE.customer(subject, cus);
        String order = id("ORD");
        STRIPE.createIntent(order, "pi_" + order, 4599);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 4599, "currency", "USD",
                "shopperId", subject, "saveForOffSession", true,
                "lines", List.of(line("L1", "org_a", "Groceries", "SELF", 1, 4000, 200, 399))), "create-" + order),
                201);
        // The PaymentIntent saves the method on the shopper's own Customer, the one the list reads.
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing(order)).withRequestBody(WireMock.containing("customer=" + cus))
                .withRequestBody(WireMock.containing("setup_future_usage=off_session")));

        // Stripe attached the confirmed Apple Pay card (and an earlier Link method); no webhook arrived.
        String applePay = "pm_apple_" + subject;
        String link = "pm_link_" + subject;
        STRIPE.customerWallet(cus, null, walletCard(applePay, cus, "visa", "4242", "apple_pay"),
                typed(link, cus, "link"), typed("pm_klarna_" + subject, cus, "klarna"));
        TestSupport.as(TestSupport.customer(subject));
        JsonNode wallet = ok(send("GET", "/v1/customers/me/payment-methods", null, null));
        assertThat(wallet.path("stripeCustomerId").asString()).isEqualTo(cus);
        JsonNode list = wallet.path("items");
        assertThat(list.size()).as("types AfroTransact does not offer are left out").isEqualTo(2);
        assertThat(list.get(0).path("id").asString()).isEqualTo(applePay);
        assertThat(list.get(0).path("wallet").asString()).isEqualTo("apple_pay");
        assertThat(list.get(0).path("last4").asString()).isEqualTo("4242");
        assertThat(list.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(list.get(1).path("type").asString()).isEqualTo("link");
        assertThat(list.get(1).path("isDefault").asBoolean()).isFalse();
        WIREMOCK.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/customers/" + cus
                + "/payment_methods")));
        // Without a default at Stripe the first method becomes it, at Stripe too (off-session charges use it).
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/customers/" + cus))
                .withRequestBody(WireMock.containing("[default_payment_method]=" + applePay)));
        // The local copy now knows it: making it the default or removing it needs no webhook either.
        JsonNode def = ok(send("POST", "/v1/customers/me/payment-methods/" + link + "/default", null, null));
        assertThat(def.path("isDefault").asBoolean()).isTrue();

        // The default was changed and the Apple Pay card removed in the Stripe Dashboard: the list follows.
        STRIPE.customerWallet(cus, link, typed(link, cus, "link"));
        list = items();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("id").asString()).isEqualTo(link);
        assertThat(list.get(0).path("isDefault").asBoolean()).isTrue();

        // Stripe unreachable: the last known wallet is listed.
        STRIPE.customerWalletDown(cus);
        assertThat(items().get(0).path("id").asString()).isEqualTo(link);
    }
}
