package com.afrotransact.payments.it;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;

/** Shared set-up of the membership ITs (S323): shoppers with a saved card, tiers, stubbed fee charges. */
abstract class MembershipSupport extends PaymentsIntegrationTest {

    private static final AtomicInteger CARDS = new AtomicInteger(1000);

    /** A shopper with a Stripe Customer and a default saved card that no other test run used (random expiry). */
    String shopperWithCard() {
        String customer = id("cust");
        ThreadLocalRandom r = ThreadLocalRandom.current();
        card(customer, String.format("%04d", CARDS.incrementAndGet() % 10_000), 1 + r.nextInt(12),
                2030 + r.nextInt(60));
        return customer;
    }

    /** Gives the shopper a saved default card; the same number ending and expiry are the same card to the guard. */
    void card(String customer, String last4, int expMonth, int expYear) {
        String stripeCustomer = "cus_" + customer.toLowerCase().replace("-", "");
        String method = "pm_" + customer.toLowerCase().replace("-", "") + "_" + last4;
        jdbc.sql("insert into stripe_customer (customer_id, stripe_customer_id, default_payment_method_id, created_at) "
                + "values (:c, :s, null, now()) on conflict (customer_id) do nothing")
                .param("c", customer).param("s", stripeCustomer).update();
        jdbc.sql("insert into customer_payment_method (id, customer_id, type, brand, last4, exp_month, exp_year, "
                + "verified, created_at) values (:id, :c, 'CARD', 'visa', :last4, :em, :ey, true, now())")
                .param("id", method).param("c", customer).param("last4", last4).param("em", expMonth)
                .param("ey", expYear).update();
        jdbc.sql("update stripe_customer set default_payment_method_id = :id where customer_id = :c")
                .param("id", method).param("c", customer).update();
    }

    /** Gives {@code customer} a saved card that is the same card as {@code other}'s. */
    void sameCardAs(String customer, String other) {
        Map<String, Object> row = jdbc.sql("select last4, exp_month, exp_year from customer_payment_method "
                + "where customer_id = :c").param("c", other).query().singleRow();
        card(customer, (String) row.get("last4"), ((Number) row.get("exp_month")).intValue(),
                ((Number) row.get("exp_year")).intValue());
    }

    /** A tier created through the API by staff (published at once). */
    JsonNode tier(String code, int sortOrder, long monthly, long yearly, int trialDays,
                  List<Map<String, Object>> benefits) {
        TestSupport.as(TestSupport.SUPER_ADMIN);
        return ok(send("POST", "/v1/memberships/admin/tiers", Map.of("code", code, "name", code.toUpperCase(),
                "description", "Test tier", "status", "PUBLISHED", "sortOrder", sortOrder, "monthlyPriceMinor",
                monthly, "yearlyPriceMinor", yearly, "trialDays", trialDays, "benefits", benefits, "reason",
                "Creating a tier for the integration tests"), null), 201);
    }

    static Map<String, Object> benefit(String code) {
        return Map.of("code", code);
    }

    static Map<String, Object> benefit(String code, Map<String, Object> numbers) {
        Map<String, Object> m = new LinkedHashMap<>(numbers);
        m.put("code", code);
        return m;
    }

    String tierCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toLowerCase();
    }

    /** Stubs a successful fee charge of this customer: the intent, its capture and the Stripe fee. */
    String chargeWorks(String customer, String keyPattern, long amount) {
        String pi = "pi_mem_" + UUID.randomUUID().toString().substring(0, 12);
        String charge = "ch_mem_" + UUID.randomUUID().toString().substring(0, 12);
        STRIPE.membershipIntent(customer, keyPattern, pi, amount, "requires_capture");
        STRIPE.capture(pi, charge, amount, "succeeded");
        STRIPE.chargeFees(charge, new long[] {amount, 59});
        STRIPE.refund(pi, "re_" + pi.substring(3), amount, "succeeded");
        return pi;
    }

    void chargeDeclined(String customer, String keyPattern, long amount) {
        STRIPE.membershipDeclined(customer, keyPattern, "pi_decl_" + UUID.randomUUID().toString().substring(0, 12),
                amount);
    }

    // ------------------------------------------------------------------ shopper calls

    JsonNode me(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/memberships/me", null, null));
    }

    JsonNode join(String customer, String tier, String interval) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("POST", "/v1/memberships/me/join", Map.of("tierCode", tier, "interval", interval),
                "join-" + customer + "-" + tier + "-" + interval + "-" + UUID.randomUUID()), 201);
    }

    JsonNode change(String customer, String tier, String interval) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("POST", "/v1/memberships/me/change", Map.of("tierCode", tier, "interval", interval),
                "change-" + UUID.randomUUID()));
    }

    JsonNode cancel(String customer, boolean refund) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("POST", "/v1/memberships/me/cancel", Map.of("refund", refund), null));
    }

    JsonNode entitlements(String customer) {
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("GET", "/v1/memberships/customers/" + customer + "/entitlements", null, null));
    }

    String status(JsonNode member) {
        return member.path("membership").path("status").asString();
    }

    long membershipCount(String customer) {
        return jdbc.sql("select count(*) from membership where customer_id = :c").param("c", customer)
                .query(Long.class).single();
    }
}
