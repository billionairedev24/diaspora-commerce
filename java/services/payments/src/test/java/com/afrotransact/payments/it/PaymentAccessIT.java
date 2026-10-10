package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S241: shoppers read only their own orders' payments; PaymentSheet ephemeral keys for the shopper's customer. */
class PaymentAccessIT extends PaymentsIntegrationTest {

    @Test
    void onlyTheOrdersShopperStaffAndServicesReadAnOrdersPayment() {
        String order = id("ORD");
        String shopper = id("shopper").toLowerCase(Locale.ROOT);
        long amount = 2000 + 500 + 165;
        STRIPE.createIntent(order, "pi_" + order, amount);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        // Like orders-service: the order's customer id travels in the metadata.
        ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_shopper", "amountMinor", amount,
                "currency", "USD", "metadata", Map.of("order_number", "AT-" + order, "customer_id", shopper),
                "lines", List.of(line("L1", "org_a", "Groceries", "SELF", 2, 2000, 500, 165))), "create-" + order),
                201);

        TestSupport.as(TestSupport.customer(shopper));
        JsonNode own = ok(send("GET", "/v1/payments?orderId=" + order, null, null));
        assertThat(own.path("orderId").asString()).isEqualTo(order);

        TestSupport.as(TestSupport.customer("someone-else-" + shopper));
        problem(send("GET", "/v1/payments?orderId=" + order, null, null), 403, "payments.not-your-order");

        TestSupport.as(TestSupport.FINANCE);
        assertThat(ok(send("GET", "/v1/payments?orderId=" + order, null, null)).path("orderId").asString())
                .isEqualTo(order);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        assertThat(ok(send("GET", "/v1/payments?orderId=" + order, null, null)).path("orderId").asString())
                .isEqualTo(order);
    }

    @Test
    void paymentSheetEphemeralKeyCreatesTheCustomerOnce() {
        String shopper = id("sheet").toLowerCase(Locale.ROOT);
        String cus = "cus_" + shopper.replace("-", "");
        STRIPE.customer(shopper, cus);
        STRIPE.ephemeralKey(cus, "ephkey_" + shopper.replace("-", ""), 4_102_444_800L);
        TestSupport.as(TestSupport.customer(shopper));

        JsonNode key = ok(send("POST", "/v1/customers/me/ephemeral-keys", null, "ek-1-" + shopper), 201);
        assertThat(key.path("customerId").asString()).isEqualTo(cus);
        assertThat(key.path("ephemeralKeySecret").asString()).startsWith("ek_test_");
        assertThat(key.path("expiresAt").asString()).isEqualTo("2100-01-01T00:00:00Z");
        ok(send("POST", "/v1/customers/me/ephemeral-keys", Map.of("stripeVersion", "2024-06-20"), "ek-2-" + shopper),
                201);
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/customers"))
                .withRequestBody(WireMock.containing(shopper)));
        List<LoggedRequest> keys = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/ephemeral_keys")).withRequestBody(WireMock.containing("customer=" + cus)));
        assertThat(keys).hasSize(2);
        assertThat(keys.getLast().getHeader("Stripe-Version")).isEqualTo("2024-06-20");

        problem(send("POST", "/v1/customers/me/ephemeral-keys", Map.of("stripeVersion", "latest"), null), 422,
                "payments.invalid-request");
        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/customers/me/ephemeral-keys", null, null), 403, "payments.customer-only");
    }
}
