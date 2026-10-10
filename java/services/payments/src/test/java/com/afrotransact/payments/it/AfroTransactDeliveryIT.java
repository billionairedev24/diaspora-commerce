package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S255 AfroTransact Delivery from the seller's store: quotes from the fee schedule's delivery pricing, the third
 * fulfilment mode in the split and ledger (customer charge → 4200, seller fee → 4250, no FBA fee) and the fault rules
 * of refunds. Uses the seed version (v1 launch defaults: customer $4.99 + $0.85/mi, min $5.99, max $24.99, 25 mi;
 * seller $2 per package).
 */
class AfroTransactDeliveryIT extends PaymentsIntegrationTest {

    private static final String PLACED = "2026-02-01T00:00:00Z";

    @Test
    void quotesChargeTheCustomerByDistanceAndTheSellerAFlatFee() {
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode q = ok(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", "org_c",
                "distanceMiles", 7.4, "packages", 1, "at", PLACED), null));
        assertThat(q.path("available").asBoolean()).isTrue();
        assertThat(q.path("customerChargeMinor").asLong()).isEqualTo(1128);
        assertThat(q.path("sellerFeeMinor").asLong()).isEqualTo(200);
        assertThat(q.path("scheduleVersion").asInt()).isEqualTo(1);
        assertThat(q.path("sellerFeeSource").asString()).isEqualTo("DEFAULT");

        JsonNode far = ok(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", "org_c",
                "distanceMiles", 25.5, "at", PLACED), null));
        assertThat(far.path("available").asBoolean()).isFalse();
        assertThat(far.path("unavailableReason").asString()).isEqualTo("BEYOND_MAX_RADIUS");

        JsonNode retail = ok(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", RETAIL,
                "distanceMiles", 1, "packages", 2, "at", PLACED), null));
        assertThat(retail.path("sellerFeeMinor").asLong()).isZero();
        assertThat(retail.path("sellerFeeSource").asString()).isEqualTo("RETAIL");
        assertThat(retail.path("customerChargeMinor").asLong()).isEqualTo(599);

        TestSupport.as(TestSupport.seller("org_c", "seller.owner", "payouts:request"));
        assertThat(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", "org_c", "distanceMiles", 1),
                null).statusCode()).isEqualTo(403);
    }

    @Test
    void deliveryLinesBookShippingTo4200AndTheSellerFeeTo4250ThenFollowTheFaultRules() {
        String order = id("ORD");
        String orgC = id("org_dc").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> lines = List.of(deliveryLine("D1", orgC, 2, 3000, 1128, 248, 200),
                line("A1", "org_a", "Groceries", "SELF", 1, 1500, 500, 124));
        STRIPE.createIntent(order, "pi_" + order, 6500);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode created = ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_shopper",
                "amountMinor", 6500, "currency", "USD", "placedAt", PLACED, "lines", lines), "create-" + order), 201);
        String paymentId = created.path("id").asString();
        STRIPE.retrieveIntent("pi_" + order, 6500, "requires_capture");
        ok(send("GET", "/v1/payments/" + paymentId + "?refresh=true", null, null));
        STRIPE.chargeFees("ch_" + order, new long[] {6500, 219});
        capture(paymentId, order, order + "-S1", List.of(), 6500, 219, true);

        assertEveryJournalBalances();
        assertThat(balance(order, "4200", "")).isEqualTo(1128);
        assertThat(balance(order, "4250", "")).isEqualTo(200);
        assertThat(balance(order, "4100", "")).isZero();
        assertThat(balance(order, "4000", "")).isEqualTo(240 + 120);
        assertThat(balance(order, "2000", orgC)).isEqualTo(3000 - 240 - 200 - 109);
        assertThat(balance(order, "2000", "org_a")).isEqualTo(1500 + 500 - 120 - 72);
        assertThat(-balance(order, "5000", "")).isEqualTo(38);

        TestSupport.as(TestSupport.FINANCE);
        JsonNode split = ok(send("GET", "/v1/orders/" + order + "/split", null, null));
        for (JsonNode r : split.path("recipients")) {
            if (r.path("recipient").asString().equals(orgC)) {
                assertThat(r.path("deliveryFeesMinor").asLong()).isEqualTo(200);
                assertThat(r.path("fbaFeesMinor").asLong()).isZero();
                assertThat(r.path("netMinor").asLong()).isEqualTo(2451);
            }
            if (r.path("kind").asString().equals("PLATFORM")) {
                assertThat(r.path("shippingMinor").asLong()).isEqualTo(1128);
                assertThat(r.path("deliveryFeesMinor").asLong()).isEqualTo(200);
            }
        }
        JsonNode payment = ok(send("GET", "/v1/payments/" + paymentId, null, null));
        JsonNode d1 = payment.path("lines").get(0);
        assertThat(d1.path("fulfilment").asString()).isEqualTo("AFROTRANSACT_DELIVERY");
        assertThat(d1.path("deliveryFeeMinor").asLong()).isEqualTo(200);
        assertThat(d1.path("deliveryFeeChargedMinor").asLong()).isEqualTo(200);

        // Customer at fault: the delivery charge is not refundable …
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("fault", "CUSTOMER", "lines",
                List.of(Map.of("lineId", "D1", "shippingMinor", 100))), "refund-x-" + order), 422,
                "payments.delivery-charge-not-refundable");
        // … and half the items give the seller half its fee back (Dr 4250 100, seller debited 1500 − 120 − 100).
        STRIPE.refund("pi_" + order, "re_c_" + order, 1624, "succeeded");
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("fault", "CUSTOMER",
                "lines", List.of(Map.of("lineId", "D1", "itemsMinor", 1500, "taxMinor", 124))),
                "refund-c-" + order), 201);
        assertThat(refund.path("lines").get(0).path("deliveryFeeReversedMinor").asLong()).isEqualTo(100);
        assertEveryJournalBalances();
        assertThat(balance(order, "4250", "")).isEqualTo(100);
        assertThat(balance(order, "2000", orgC)).isEqualTo(2451 - 1280);

        // Seller at fault (default): the rest incl. the whole delivery charge back to the customer, fee kept.
        STRIPE.refund("pi_" + order, "re_s_" + order, 1500 + 1128 + 124, "succeeded");
        JsonNode sellerFault = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines",
                List.of(Map.of("lineId", "D1", "itemsMinor", 1500, "shippingMinor", 1128, "taxMinor", 124))),
                "refund-s-" + order), 201);
        assertThat(sellerFault.path("lines").get(0).path("deliveryFeeReversedMinor").asLong()).isZero();
        assertEveryJournalBalances();
        assertThat(balance(order, "4200", "")).isZero();
        assertThat(balance(order, "4250", "")).isEqualTo(100);
        assertThat(balance(order, "2000", orgC)).isEqualTo(2451 - 1280 - (1500 - 120));
    }

    @Test
    void theStaffEditorSavesDeliveryPricingWithBandsAndSellerOverrides() {
        Map<String, Object> version = new HashMap<>(Map.of("effectiveFrom", "2090-01-01T00:00:00Z",
                "categoryBps", Map.of("groceries", 800), "defaultCommissionBps", 1000, "cardPctBps", 290,
                "cardFixedMinor", 30, "fbaPerUnitMinor", 325, "note", "Delivery bands", "currency", "USD"));
        version.put("deliveryPricing", Map.of("maxRadiusMiles", 12,
                "customerCharge", Map.of("mode", "DISTANCE", "baseMinor", 500, "perMileMinor", 100, "minMinor", 0,
                        "bands", List.of(Map.of("fromMiles", 0, "toMiles", 3, "feeMinor", 399),
                                Map.of("fromMiles", 3, "toMiles", 10, "feeMinor", 699))),
                "sellerFee", Map.of("mode", "FLAT", "flatPerPackageMinor", 150),
                "sellerOverrides", List.of(Map.of("orgId", "org_vip", "sellerFee",
                        Map.of("mode", "FLAT", "flatPerPackageMinor", 0)))));
        TestSupport.as(TestSupport.FINANCE);
        JsonNode created = ok(send("POST", "/v1/fee-schedules", version, null), 201);
        JsonNode dp = created.path("deliveryPricing");
        assertThat(dp.path("enabled").asBoolean()).isTrue();
        assertThat(dp.path("customerCharge").path("bands")).hasSize(2);
        assertThat(dp.path("sellerOverrides").get(0).path("orgId").asString()).isEqualTo("org_vip");
        try {
            JsonNode listed = ok(send("GET", "/v1/fee-schedules", null, null));
            assertThat(listed.get(0).path("deliveryPricing").path("maxRadiusMiles").decimalValue())
                    .isEqualByComparingTo("12");
            TestSupport.as(TestSupport.ORDERS_SERVICE);
            JsonNode band = ok(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", "org_vip",
                    "distanceMiles", 4.2, "packages", 2, "at", "2090-02-01T00:00:00Z"), null));
            assertThat(band.path("customerChargeMinor").asLong()).isEqualTo(699);
            assertThat(band.path("sellerFeeMinor").asLong()).isZero();
            assertThat(band.path("sellerFeeSource").asString()).isEqualTo("OVERRIDE");
            JsonNode other = ok(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", "org_x",
                    "distanceMiles", 11, "packages", 2, "at", "2090-02-01T00:00:00Z"), null));
            assertThat(other.path("customerChargeMinor").asLong()).isEqualTo(500 + 1100);
            assertThat(other.path("sellerFeeMinor").asLong()).isEqualTo(300);
            JsonNode beyond = ok(send("POST", "/v1/delivery-pricing/quote", Map.of("sellerOrgId", "org_x",
                    "distanceMiles", 12.5, "at", "2090-02-01T00:00:00Z"), null));
            assertThat(beyond.path("available").asBoolean()).isFalse();
        } finally {
            TestSupport.as(TestSupport.FINANCE);
            ok(send("POST", "/v1/fee-schedules/" + created.path("id").asString() + "/cancel",
                    Map.of("reason", "IT cleanup"), null));
        }
    }

    private static Map<String, Object> deliveryLine(String lineId, String org, int qty, long items, long shipping,
                                                    long tax, long sellerFee) {
        Map<String, Object> l = new HashMap<>(line(lineId, org, "Groceries", "AFROTRANSACT_DELIVERY", qty, items,
                shipping, tax));
        l.put("deliveryFeeMinor", sellerFee);
        return l;
    }
}
