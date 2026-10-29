package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerMetricsService;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S296: reward rules in our DB. Metrics arrive as events (de-duplicated on the event id), a qualifying organisation
 * gets one grant per rule (max grants respected), applied locally: free days move the first charge out, a credit adds
 * to the platform credit balance the next invoices use first, a percentage is a coupon for the next N invoices (coupons
 * stack). Sellers see their progress; staff preview who qualifies.
 */
class RewardsIT extends SellerIntegrationTest {

    private static final String RULES = "/v1/billing/reward-rules";

    private SellerMetricsService metrics;
    private BillingService billing;

    @BeforeEach
    void wire(@Autowired SellerMetricsService sellerMetrics, @Autowired BillingService billingService) {
        this.metrics = sellerMetrics;
        this.billing = billingService;
    }

    @Test
    void salesWithinSixtyDaysOfApprovalEarnAMonthFreeOnce() {
        String owner = unique("rw");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String rule = createRule(Map.of("name", "1 month free at $1,000 " + owner, "kind", "SELLER",
                "metric", "GROSS_SALES", "threshold", 100_000, "withinDaysOfApproval", 60, "rewardType", "FREE_DAYS",
                "freeDays", 30, "eligiblePlans", List.of("seller_growth"), "status", "ACTIVE"));

        TestActors.as(TestActors.applicant(owner));
        JsonNode available = progress(org, rule);
        assertThat(available.path("value").asLong()).isZero();
        assertThat(available.path("label").asString()).isEqualTo("$0 of $1,000 in sales → 1 month free");

        UUID first = UUID.randomUUID();
        assertThat(metrics.shipmentDelivered(first, org, 64_000, "USD", Instant.now())).isTrue();
        assertThat(metrics.shipmentDelivered(first, org, 64_000, "USD", Instant.now())).as("replayed event")
                .isFalse();
        JsonNode at64 = progress(org, rule);
        assertThat(at64.path("value").asLong()).isEqualTo(64_000);
        assertThat(at64.path("percent").asInt()).isEqualTo(64);
        assertThat(at64.path("label").asString()).isEqualTo("$640 of $1,000 in sales → 1 month free");
        assertThat(grants(rule)).isEmpty();

        Instant trialBefore = jdbc.sql("select trial_ends_at from subscription where org_id = :o").param("o", org)
                .query(Timestamp.class).single().toInstant();
        metrics.shipmentDelivered(UUID.randomUUID(), org, 40_000, "USD", Instant.now());
        List<Map<String, Object>> granted = grants(rule);
        assertThat(granted).hasSize(1);
        Map<String, Object> g = granted.getFirst();
        assertThat(g.get("status")).isEqualTo("APPLIED");
        assertThat(g.get("summary")).isEqualTo("1 month free");
        assertThat(g.get("metric_value")).isEqualTo(104_000L);
        Instant trialAfter = jdbc.sql("select trial_ends_at from subscription where org_id = :o").param("o", org)
                .query(Timestamp.class).single().toInstant();
        assertThat(trialAfter).as("the first charge moved out by 30 days").isEqualTo(trialBefore.plusSeconds(30L * 86400));
        assertThat(column(org, "anchor_date")).as("and the billing cycle restarts there")
                .isEqualTo(column(org, "trial_ends_at"));
        assertThat(lastEvent(SellerEvents.BILLING_GRANT_APPLIED, org).path("summary").asString())
                .isEqualTo("1 month free");

        metrics.shipmentDelivered(UUID.randomUUID(), org, 90_000, "USD", Instant.now());
        assertThat(grants(rule)).as("once per seller").hasSize(1);
        TestActors.as(TestActors.applicant(owner));
        Response rewards = get("/v1/orgs/" + org + "/billing/rewards");
        assertThat(rewards.body().path("available").valueStream().map(a -> a.path("ruleId").asString()))
                .doesNotContain(rule);
        assertThat(rewards.body().path("earned").valueStream().map(e -> e.path("ruleId").asString())).contains(rule);
        end(rule);
    }

    @Test
    void creditsRespectMaxGrantsAndPercentagesBecomeRepeatingCoupons() {
        String ownerA = unique("ca");
        String ownerB = unique("cb");
        String orgA = approvedSeller(ownerA, "seller_growth", Cadence.QUARTERLY);
        String orgB = approvedSeller(ownerB, "seller_growth", Cadence.MONTHLY);
        String credit = createRule(Map.of("name", "Two orders " + ownerA, "metric", "COMPLETED_ORDERS",
                "threshold", 2, "rewardType", "CREDIT", "creditCents", 2500, "maxGrants", 1, "status", "ACTIVE"));
        for (String org : List.of(orgA, orgB)) {
            metrics.shipmentDelivered(UUID.randomUUID(), org, 1_000, "USD", Instant.now());
            metrics.shipmentDelivered(UUID.randomUUID(), org, 1_000, "USD", Instant.now());
        }
        List<Map<String, Object>> credits = grants(credit);
        assertThat(credits).as("max grants 1").hasSize(1);
        assertThat(credits.getFirst().get("org_id")).isEqualTo(orgA);
        assertThat(column(orgA, "credit_balance_cents")).isEqualTo("2500");

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Map<String, Object> previewBody = new HashMap<>(Map.of("name", "Two orders", "metric", "COMPLETED_ORDERS",
                "threshold", 2, "rewardType", "CREDIT", "creditCents", 2500, "maxGrants", 1));
        previewBody.put("ruleId", credit);
        Response preview = post(RULES + "/preview", previewBody);
        assertThat(preview.status()).isEqualTo(200);
        JsonNode a = row(preview, orgA);
        assertThat(a.path("alreadyGranted").asBoolean()).isTrue();
        assertThat(a.path("qualifies").asBoolean()).isFalse();
        JsonNode b = row(preview, orgB);
        assertThat(b.path("value").asLong()).isEqualTo(2);
        assertThat(b.path("qualifies").asBoolean()).as("the preview ignores max grants").isTrue();
        end(credit);

        // 20 % off the next 3 invoices for 2 live listings: quarterly cadence → a 9-month repeating coupon.
        String pct = createRule(Map.of("name", "Two listings " + ownerA, "metric", "ACTIVE_LISTINGS",
                "threshold", 2, "rewardType", "PERCENT_OFF", "percentOff", 20, "durationInvoices", 3,
                "status", "ACTIVE"));
        assertThat(metrics.offerChanged(UUID.randomUUID(), "offer-" + ownerA + "-1", orgA, true, Instant.now()))
                .isTrue();
        assertThat(grants(pct)).isEmpty();
        metrics.offerChanged(UUID.randomUUID(), "offer-" + ownerA + "-2", orgA, true, Instant.now());
        List<Map<String, Object>> discounts = grants(pct);
        assertThat(discounts).hasSize(1);
        assertThat(discounts.getFirst().get("status")).isEqualTo("APPLIED");
        assertThat(discounts.getFirst().get("remaining_invoices")).isEqualTo(3);

        // The coupon comes off the next invoice's plan fee, after the platform credit has been used first.
        jdbc.sql("update subscription set trial_ends_at = null, status = 'ACTIVE', current_period_end = now() - "
                + "interval '1 hour', anchor_date = now() - interval '1 hour' where org_id = :o").param("o", orgA)
                .update();
        billing.renewDueSubscriptions();
        Map<String, Object> invoice = jdbc.sql("select * from invoice where org_id = :o").param("o", orgA).query()
                .singleRow();
        assertThat(invoice.get("subtotal_cents")).isEqualTo(11115L);
        assertThat(invoice.get("discount_cents")).as("20% of $111.15").isEqualTo(2223L);
        assertThat(invoice.get("amount_due_cents")).isEqualTo(8892L);
        assertThat(jdbc.sql("select source || ':' || amount_cents from invoice_payment where org_id = :o order by "
                + "created_at").param("o", orgA).query(String.class).list()).containsExactly("CREDIT:2500",
                "CARD:6392");
        assertThat(grants(pct).getFirst().get("remaining_invoices")).as("one invoice used").isEqualTo(2);

        TestActors.as(TestActors.staff(unique("billing"), SellerPermissions.BILLING));
        Response log = get("/v1/billing/grants?ruleId=" + pct);
        assertThat(log.body().path("total").asLong()).isEqualTo(1);
        assertThat(log.body().path("items").get(0).path("summary").asString())
                .isEqualTo("20% off your next 3 invoices");
        assertThat(post(RULES, Map.of("name", "x")).status()).as("seller:billing can't edit rules").isEqualTo(403);
        end(pct);
    }

    @Test
    void ruleValidationAndOptimisticLocking() {
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response bad = post(RULES, Map.of("name", "", "metric", "GROSS_SALES", "rewardType", "PERCENT_OFF"));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().path("fieldErrors").has("threshold")).isTrue();
        assertThat(bad.body().path("fieldErrors").has("percentOff")).isTrue();
        Response created = post(RULES, Map.of("name", "Draft rule", "metric", "DAYS_SINCE_APPROVAL",
                "threshold", 90, "rewardType", "CREDIT", "creditCents", 1000));
        assertThat(created.text("/status")).isEqualTo("DRAFT");
        assertThat(created.text("/conditionLabel")).isEqualTo("90 days on AfroTransact");
        String id = created.text("/id");
        long version = created.body().path("version").asLong();
        Map<String, Object> edit = new HashMap<>(Map.of("name", "Edited", "metric", "DAYS_SINCE_APPROVAL",
                "threshold", 120, "rewardType", "CREDIT", "creditCents", 1000));
        edit.put("expectedVersion", version);
        assertThat(put(RULES + "/" + id, edit).text("/name")).isEqualTo("Edited");
        assertThat(put(RULES + "/" + id, edit).status()).as("stale version").isEqualTo(409);
        assertThat(post(RULES + "/" + id + "/status", Map.of("status", "ENDED")).text("/status")).isEqualTo("ENDED");
    }

    private String createRule(Map<String, Object> body) {
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response r = post(RULES, body);
        assertThat(r.status()).isEqualTo(201);
        TestActors.reset();
        return r.text("/id");
    }

    private void end(String rule) {
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        post(RULES + "/" + rule + "/status", Map.of("status", "ENDED"));
        TestActors.reset();
    }

    private JsonNode progress(String org, String rule) {
        return get("/v1/orgs/" + org + "/billing/rewards").body().path("available").valueStream()
                .filter(a -> a.path("ruleId").asString().equals(rule)).findFirst().orElseThrow();
    }

    private List<Map<String, Object>> grants(String rule) {
        return jdbc.sql("select * from billing_grant where rule_id = cast(:r as uuid)").param("r", rule)
                .query().listOfRows();
    }

    private static JsonNode row(Response preview, String org) {
        return preview.body().path("rows").valueStream().filter(r -> r.path("orgId").asString().equals(org))
                .findFirst().orElseThrow();
    }

    private String column(String org, String column) {
        return jdbc.sql("select " + column + "::text from subscription where org_id = :o").param("o", org)
                .query(String.class).optional().orElse(null);
    }
}
