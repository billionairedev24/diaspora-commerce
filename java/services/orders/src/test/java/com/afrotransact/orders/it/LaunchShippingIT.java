package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Launch shipping end to end (listing rules D16, D18): the staff-set flat fee is what checkout charges, the carrier
 * option is refused while the staff switch is off, and every change of the fee is audited with a reason.
 */
class LaunchShippingIT extends OrdersIntegrationTest {

    @BeforeEach
    void launchDefault() {
        sellerCarrierShipping(false);
    }

    private String cart(String bearer, long price) {
        String atin = id("ATS");
        String org = id("org-sel");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", price, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        return org + ":SELLER";
    }

    private JsonNode checkout(long price, String selection) {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String pkg = cart(bearer, price);
        UPSTREAMS.paymentIntent(customer, UUID.randomUUID().toString());
        TestSupport.as(TestSupport.customer(customer));
        String body = """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"%s"%s}]}
                """.formatted(pkg, selection);
        return ok(send("POST", "/v1/checkout", body, "Idempotency-Key", "idem-" + customer, "Authorization",
                "Bearer " + bearer), 201);
    }

    private void checkoutRefused(long price, String option) {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String pkg = cart(bearer, price);
        TestSupport.as(TestSupport.customer(customer));
        String body = """
                {"addressId":"addr-1","deliverySelections":[{"packageId":"%s","option":"%s"}]}
                """.formatted(pkg, option);
        problem(send("POST", "/v1/checkout", body, "Idempotency-Key", "idem-" + customer, "Authorization",
                "Bearer " + bearer), 422, "orders.invalid-delivery-selection");
    }

    @Test
    void theFlatFeeIsPublicAndWhatCheckoutCharges() {
        JsonNode settings = ok(send("GET", "/v1/public/shipping-settings", null));
        assertThat(settings.path("flatFeeMinor").asLong()).isEqualTo(599);
        assertThat(settings.path("freeAboveMinor").isNull()).isTrue();
        assertThat(settings.path("sellerCarrierShippingEnabled").asBoolean()).isFalse();

        for (long price : new long[] {1000, 3000, 9000}) {
            JsonNode created = checkout(price, "");
            assertThat(created.path("shippingMinor").asLong()).as("no choice, items " + price)
                    .isEqualTo(599);
            assertThat(created.path("shipments").get(0).path("deliveryMethod").asString())
                    .isEqualTo("AFROTRANSACT_DELIVERY");
        }
        assertThat(checkout(3000, ",\"option\":\"afrotransact_delivery\"")
                .path("shippingMinor").asLong()).isEqualTo(599);
    }

    @Test
    void theRetiredOptionsAreRefusedInPlainWordsWhileCarrierShippingIsOff() {
        for (String option : new String[] {"carrier", "standard", "express"}) {
            checkoutRefused(3000, option);
        }
    }

    @Test
    void staffChangeTheFeeWithAReasonAndTheNextCheckoutChargesIt() {
        TestSupport.as(TestSupport.SUPER_ADMIN);
        String noReason = """
                {"flatFeeMinor":699,"freeAboveMinor":6000,"sellerCarrierShippingEnabled":false,"expectedVersion":1}
                """;
        problem(send("PUT", "/v1/shipping-settings/USD", noReason), 422, "orders.reason-required");
        String change = """
                {"flatFeeMinor":699,"freeAboveMinor":6000,"sellerCarrierShippingEnabled":false,"expectedVersion":1,
                 "reason":"Fuel went up this month"}
                """;
        JsonNode saved = ok(send("PUT", "/v1/shipping-settings/USD", change));
        assertThat(saved.path("version").asLong()).isEqualTo(2);
        problem(send("PUT", "/v1/shipping-settings/USD", change), 409, "orders.shipping-settings-version-mismatch");

        assertThat(checkout(3000, "").path("shippingMinor").asLong()).isEqualTo(699);
        assertThat(checkout(6000, "").path("shippingMinor").asLong()).as("free above 60.00")
                .isZero();

        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode history = ok(send("GET", "/v1/shipping-settings/USD/changes", null));
        assertThat(history.path("items")).hasSize(2);
        assertThat(history.path("items").get(0).path("reason").asString()).isEqualTo("Fuel went up this month");
        assertThat(history.path("items").get(0).path("before").path("flatFeeMinor").asLong()).isEqualTo(599);
        assertThat(history.path("items").get(1).path("before").isNull()).as("the launch default").isTrue();
    }

    @Test
    void shoppersAndSellersCannotChangeTheFee() {
        TestSupport.as(TestSupport.customer("cust-x"));
        assertThat(send("PUT", "/v1/shipping-settings/USD", """
                {"flatFeeMinor":1,"reason":"I would like it cheaper please"}""").statusCode()).isEqualTo(403);
    }
}
