package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Saved customer payment methods: lazy Stripe Customer, SetupIntents, webhook sync, default rules. */
class PaymentMethodsIT extends PaymentsIntegrationTest {

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
        assertThat(ok(send("GET", "/v1/customers/me/payment-methods", null, null)).size()).isZero();

        STRIPE.customer(subject, cus);
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
        JsonNode list = ok(send("GET", "/v1/customers/me/payment-methods", null, null));
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
        JsonNode pending = ok(send("GET", "/v1/customers/me/payment-methods", null, null)).get(1);
        assertThat(pending.path("type").asString()).isEqualTo("us_bank_account");
        assertThat(pending.path("verified").asBoolean()).isFalse();
        assertThat(pending.path("bankName").asString()).isEqualTo("STRIPE TEST BANK");
        problem(send("POST", "/v1/customers/me/payment-methods/" + ach + "/default", null, null), 409,
                "payments.method-unverified");
        webhook(event(id("evt"), "setup_intent.succeeded", "{\"id\":\"seti_" + subject + "\",\"object\":"
                + "\"setup_intent\",\"status\":\"succeeded\",\"customer\":\"" + cus + "\",\"payment_method\":\""
                + ach + "\"}"));
        assertThat(ok(send("GET", "/v1/customers/me/payment-methods", null, null)).get(1).path("verified")
                .asBoolean()).isTrue();

        problem(send("DELETE", "/v1/customers/me/payment-methods/" + visa, null, null), 409,
                "payments.default-method-required");
        JsonNode def = ok(send("POST", "/v1/customers/me/payment-methods/" + ach + "/default", null, null));
        assertThat(def.path("isDefault").asBoolean()).isTrue();
        STRIPE.paymentMethod(card(visa, null, "visa", "4242"), visa);
        assertThat(send("DELETE", "/v1/customers/me/payment-methods/" + visa, null, null).statusCode())
                .isEqualTo(204);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_methods/" + visa
                + "/detach")).withHeader("Idempotency-Key", WireMock.equalTo("detach:" + visa)));
        list = ok(send("GET", "/v1/customers/me/payment-methods", null, null));
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
        assertThat(ok(send("GET", "/v1/customers/me/payment-methods", null, null)).size()).isZero();
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("GET", "/v1/customers/me/payment-methods", null, null), 403, "payments.customer-only");
    }
}
