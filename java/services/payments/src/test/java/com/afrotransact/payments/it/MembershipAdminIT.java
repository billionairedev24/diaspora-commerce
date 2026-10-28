package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.MembershipBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S323 staff side against PostgreSQL: define, edit, publish and retire tiers with a reason and an audit trail, the
 * billing rules, the member list, and the numbers staff and finance read (members per tier, churn, cost of the
 * benefits, recurring revenue, credit granted).
 */
class MembershipAdminIT extends MembershipSupport {

    private static final String JOIN = ".*:join:intent";

    MembershipBillingService billing;

    @BeforeEach
    void wire(@Autowired MembershipBillingService service) {
        this.billing = service;
    }

    private Map<String, Object> body(String code, String status, long monthly, long yearly, int trial,
                                     List<Map<String, Object>> benefits, String reason, Long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", "Tier " + code);
        m.put("description", "Configured in the test");
        m.put("status", status);
        m.put("sortOrder", 50);
        m.put("monthlyPriceMinor", monthly);
        m.put("yearlyPriceMinor", yearly);
        m.put("trialDays", trial);
        m.put("benefits", benefits);
        m.put("reason", reason);
        if (version != null) {
            m.put("expectedVersion", version);
        }
        return m;
    }

    @Test
    void theSeededPlusTierIsPublishedWithItsDefaults() {
        TestSupport.as(TestSupport.customer(id("anyone")));
        JsonNode tiers = ok(send("GET", "/v1/memberships/tiers", null, null));
        JsonNode plus = null;
        for (JsonNode t : tiers) {
            if ("plus".equals(t.path("code").asString())) {
                plus = t;
            }
        }
        assertThat(plus).isNotNull();
        assertThat(plus.path("name").asString()).isEqualTo("Plus");
        assertThat(plus.path("monthlyPriceMinor").asLong()).isEqualTo(999);
        assertThat(plus.path("yearlyPriceMinor").asLong()).isEqualTo(9900);
        assertThat(plus.path("trialDays").asInt()).isEqualTo(14);
        assertThat(plus.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(plus.path("benefits").toString()).contains("FREE_DELIVERY").contains("FREE_SHIPPING")
                .contains("2500");
    }

    @Test
    void staffDefineEditPublishAndRetireTiersWithAReasonAndAnAuditTrail() {
        String code = tierCode("lab");
        TestSupport.as(TestSupport.SUPER_ADMIN);
        List<Map<String, Object>> benefits = List.of(benefit("CASHBACK", Map.of("percentBps", 200)));

        // a reason is required, and the numbers must make sense
        problem(send("POST", "/v1/memberships/admin/tiers", body(code, "DRAFT", 1500, 15000, 7, benefits, "short",
                null), null), 422, "payments.reason-required");
        JsonNode bad = problem(send("POST", "/v1/memberships/admin/tiers", body(code, "DRAFT", 1500, 99_999, 7,
                benefits, "Defining a tier for the lab", null), null), 422, "payments.membership-tier-invalid");
        assertThat(bad.path("fieldErrors").toString()).contains("yearlyPriceMinor");
        problem(send("POST", "/v1/memberships/admin/tiers", body(code, "DRAFT", 1500, 15000, 7, List.of(Map.of(
                "code", "CASHBACK")), "Defining a tier for the lab", null), null), 422,
                "payments.membership-benefit-invalid");

        JsonNode draft = ok(send("POST", "/v1/memberships/admin/tiers", body(code, "DRAFT", 1500, 15000, 7, benefits,
                "Defining a tier for the lab", null), null), 201);
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("version").asLong()).isZero();
        problem(send("POST", "/v1/memberships/admin/tiers", body(code, "DRAFT", 1500, 15000, 7, benefits,
                "Defining a tier for the lab", null), null), 409, "payments.membership-tier-exists");

        // a draft is not offered to shoppers
        TestSupport.as(TestSupport.customer(id("shopper")));
        assertThat(ok(send("GET", "/v1/memberships/tiers", null, null)).toString()).doesNotContain(code);

        // edit, then publish
        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode edited = ok(send("PUT", "/v1/memberships/admin/tiers/" + code, body(code, "DRAFT", 1600, 16000, 10,
                List.of(benefit("CASHBACK", Map.of("percentBps", 300)), benefit("FREE_RETURNS")),
                "Raising the price after the pricing review", 0L), null));
        assertThat(edited.path("version").asLong()).isEqualTo(1);
        assertThat(edited.path("monthlyPriceMinor").asLong()).isEqualTo(1600);
        problem(send("PUT", "/v1/memberships/admin/tiers/" + code, body(code, "PUBLISHED", 1600, 16000, 10,
                benefits, "Publishing with a stale version", 0L), null), 409, "payments.membership-tier-changed");
        JsonNode published = ok(send("PUT", "/v1/memberships/admin/tiers/" + code, body(code, "PUBLISHED", 1600,
                16000, 10, List.of(benefit("CASHBACK", Map.of("percentBps", 300)), benefit("FREE_RETURNS")),
                "Publishing the tier for launch", 1L), null));
        assertThat(published.path("status").asString()).isEqualTo("PUBLISHED");
        TestSupport.as(TestSupport.customer(id("shopper")));
        assertThat(ok(send("GET", "/v1/memberships/tiers", null, null)).toString()).contains(code);

        // a published tier does not go back to draft; retiring stops new sign-ups
        TestSupport.as(TestSupport.SUPER_ADMIN);
        problem(send("PUT", "/v1/memberships/admin/tiers/" + code, body(code, "DRAFT", 1600, 16000, 10, benefits,
                "Trying to unpublish the tier", 2L), null), 422, "payments.membership-tier-invalid");
        String shopper = shopperWithCard();
        chargeWorks(shopper, JOIN, 1600);
        JsonNode joined = join(shopper, code, "MONTHLY");
        assertThat(joined.path("membership").path("status").asString()).isEqualTo("TRIALING");
        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode retired = ok(send("PUT", "/v1/memberships/admin/tiers/" + code, body(code, "RETIRED", 1600, 16000,
                10, List.of(benefit("CASHBACK", Map.of("percentBps", 300)), benefit("FREE_RETURNS")),
                "Retiring the tier; members keep it", 2L), null));
        assertThat(retired.path("status").asString()).isEqualTo("RETIRED");
        TestSupport.as(TestSupport.customer(id("late")));
        assertThat(ok(send("GET", "/v1/memberships/tiers", null, null)).toString()).doesNotContain(code);
        String late = shopperWithCard();
        TestSupport.as(TestSupport.customer(late));
        problem(send("POST", "/v1/memberships/me/join", Map.of("tierCode", code, "interval", "MONTHLY"), "k-late"),
                409, "payments.membership-tier-unavailable");
        assertThat(entitlements(shopper).path("member").asBoolean()).as("current members keep a retired tier")
                .isTrue();

        // the audit trail: who, when, why, and the before and after
        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode changes = ok(send("GET", "/v1/memberships/admin/tiers/" + code + "/changes", null, null));
        assertThat(changes).hasSize(4);
        assertThat(changes.get(0).path("action").asString()).isEqualTo("RETIRED");
        assertThat(changes.get(0).path("reason").asString()).isEqualTo("Retiring the tier; members keep it");
        assertThat(changes.get(0).path("changedBy").asString()).isEqualTo("local-dev");
        assertThat(changes.get(3).path("action").asString()).isEqualTo("CREATED");
        assertThat(changes.get(2).path("beforeJson").asString()).contains("1500");
        assertThat(changes.get(2).path("afterJson").asString()).contains("1600");
    }

    @Test
    void onlyStaffWhoConfigurePaymentsEditTiersAndOnlyStaffWhoReadSeeTheMembers() {
        String code = tierCode("lab");
        TestSupport.as(TestSupport.customer(id("shopper")));
        problem(send("POST", "/v1/memberships/admin/tiers", body(code, "DRAFT", 1500, 15000, 0,
                List.of(benefit("FREE_RETURNS")), "A shopper defining a tier", null), null), 403,
                "auth.missing-permission");
        problem(send("GET", "/v1/memberships/admin/members", null, null), 403, "auth.missing-permission");
        problem(send("GET", "/v1/memberships/admin/report", null, null), 403, "auth.missing-permission");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("PUT", "/v1/memberships/admin/settings", Map.of("refundWindowDays", 3, "retryDays", List.of(1),
                "reason", "A service changing the rules", "expectedVersion", 0), null), 403, "auth.missing-permission");
    }

    @Test
    void staffSetTheRefundWindowAndRetryScheduleWithAReasonAndHistory() {
        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode before = ok(send("GET", "/v1/memberships/admin/settings", null, null));
        assertThat(before.path("retryDays").size()).isPositive();
        long version = before.path("version").asLong();
        int window = before.path("refundWindowDays").asInt();

        problem(send("PUT", "/v1/memberships/admin/settings", Map.of("refundWindowDays", 9, "retryDays", List.of(2),
                "reason", "too short", "expectedVersion", version), null), 422, "payments.reason-required");
        JsonNode changed = ok(send("PUT", "/v1/memberships/admin/settings", Map.of("refundWindowDays", 9, "retryDays",
                List.of(2, 4), "reason", "Longer window after the support review", "expectedVersion", version),
                null));
        assertThat(changed.path("refundWindowDays").asInt()).isEqualTo(9);
        assertThat(changed.path("retryDays").size()).isEqualTo(2);
        problem(send("PUT", "/v1/memberships/admin/settings", Map.of("refundWindowDays", 3, "retryDays", List.of(1),
                "reason", "Writing with a stale version of it", "expectedVersion", version), null), 409,
                "payments.membership-settings-changed");
        JsonNode history = ok(send("GET", "/v1/memberships/admin/settings/changes", null, null));
        assertThat(history.get(0).path("reason").asString()).isEqualTo("Longer window after the support review");

        // put it back for the other tests
        ok(send("PUT", "/v1/memberships/admin/settings", Map.of("refundWindowDays", window, "retryDays",
                List.of(1, 3, 5), "reason", "Restoring the defaults after the test", "expectedVersion",
                changed.path("version").asLong()), null));
    }

    @Test
    void staffSeeMembersPerTierChurnTheCostOfBenefitsAndRecurringRevenue() {
        String code = tierCode("rep");
        tier(code, 40, 1000, 10000, 0, List.of(benefit("FREE_DELIVERY", Map.of("minOrderMinor", 0)),
                benefit("CASHBACK", Map.of("percentBps", 1000))));
        String monthly = shopperWithCard();
        String yearly = shopperWithCard();
        String leaver = shopperWithCard();
        chargeWorks(monthly, JOIN, 1000);
        chargeWorks(yearly, JOIN, 10000);
        chargeWorks(leaver, JOIN, 1000);
        join(monthly, code, "MONTHLY");
        join(yearly, code, "YEARLY");
        join(leaver, code, "MONTHLY");

        // a delivery fee waived on an order and cashback on a delivered one are the cost of the benefits
        String order = id("ORD");
        STRIPE.createIntent(order, "pi_" + order, 5000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_x", "amountMinor", 5000,
                "currency", "USD", "shopperId", monthly, "lines", List.of(line("L1", "org_m", "Books", "SELF", 1,
                5000, 0, 0)), "membershipWaivers", List.of(Map.of("ref", "ship-1", "benefit", "FREE_DELIVERY",
                "amountMinor", 799, "tierCode", code))), "create-" + order), 201);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of("kind", "PRODUCT"), null));

        // one member cancels and the period ends
        cancel(leaver, false);
        TestSupport.CLOCK.advance(Duration.ofDays(32));
        billing.renewDue(1000);

        TestSupport.as(TestSupport.FINANCE);
        JsonNode report = ok(send("GET", "/v1/memberships/admin/report?from="
                + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now().plusDays(60), null,
                null));
        JsonNode figures = null;
        for (JsonNode t : report.path("tiers")) {
            if (code.equals(t.path("tierCode").asString())) {
                figures = t;
            }
        }
        assertThat(figures).isNotNull();
        assertThat(figures.path("joined").asLong()).isEqualTo(3);
        assertThat(figures.path("canceled").asLong()).isEqualTo(1);
        assertThat(figures.path("members").asLong()).as("two stay").isEqualTo(2);
        assertThat(figures.path("chargedMinor").asLong()).isEqualTo(12000);
        // 10.00 a month and 100.00 a year are 10.00 + 8.33 of monthly recurring revenue
        assertThat(figures.path("mrrMinor").asLong()).isEqualTo(1000 + 833);
        assertThat(report.path("mrrMinor").asLong()).isGreaterThanOrEqualTo(1833);
        assertThat(report.path("arrMinor").asLong()).isEqualTo(report.path("mrrMinor").asLong() * 12);
        assertThat(report.path("liveMembers").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(report.path("feesWaivedMinor").asLong()).isGreaterThanOrEqualTo(799);
        assertThat(report.path("creditGrantedMinor").asLong()).as("10% of 50.00").isGreaterThanOrEqualTo(500);
        assertThat(report.path("benefitCostMinor").asLong()).isEqualTo(report.path("feesWaivedMinor").asLong()
                + report.path("creditGrantedMinor").asLong());
        assertThat(report.path("netRevenueMinor").asLong()).isEqualTo(report.path("chargedMinor").asLong()
                - report.path("refundedMinor").asLong());
        boolean costOfDelivery = false;
        for (JsonNode c : report.path("costs")) {
            costOfDelivery |= "FREE_DELIVERY".equals(c.path("benefit").asString());
        }
        assertThat(costOfDelivery).isTrue();

        // the member list and a member's timeline
        JsonNode list = ok(send("GET", "/v1/memberships/admin/members?tier=" + code + "&limit=10", null, null));
        assertThat(list).hasSize(3);
        JsonNode canceled = ok(send("GET", "/v1/memberships/admin/members?tier=" + code + "&status=CANCELED", null,
                null));
        assertThat(canceled).hasSize(1);
        assertThat(canceled.get(0).path("customerId").asString()).isEqualTo(leaver);
        JsonNode events = ok(send("GET", "/v1/memberships/admin/members/" + canceled.get(0).path("id").asString()
                + "/events", null, null));
        assertThat(events.toString()).contains("JOINED").contains("CANCEL_SCHEDULED").contains("ENDED");
        JsonNode theirMembership = ok(send("GET", "/v1/memberships/customers/" + monthly, null, null));
        assertThat(theirMembership.path("membership").path("tierCode").asString()).isEqualTo(code);
        assertThat(UUID.fromString(theirMembership.path("membership").path("id").asString())).isNotNull();
    }
}
