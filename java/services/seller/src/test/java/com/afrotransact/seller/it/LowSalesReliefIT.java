package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.application.RewardService;
import com.afrotransact.seller.application.SellerMetricsService;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S299: "1st month free (the trial) and 2nd month free if the seller sold less than $1,000". A LESS_THAN rule anchored
 * at the subscription's start is never decided by sales events: the window-end job checks it once, 24 h before the
 * 30-day trial ends, and the free days move trial_end at Stripe (proration_behavior=none, the grant's own
 * idempotency key) before Stripe creates the first invoice. Exactly $1,000 does not qualify; a second run decides
 * nothing again.
 */
class LowSalesReliefIT extends SellerIntegrationTest {

    private static final String RULES = "/v1/billing/reward-rules";

    private SellerMetricsService metrics;
    private RewardService rewards;

    @BeforeEach
    void wire(@Autowired SellerMetricsService sellerMetrics, @Autowired RewardService rewardService) {
        this.metrics = sellerMetrics;
        this.rewards = rewardService;
    }

    @Test
    void lowSalesInTheFirstThirtyDaysMakeTheSecondMonthFreeBeforeTheFirstInvoice() {
        String quietOwner = unique("lq");
        String quiet = approvedSeller(quietOwner, "seller_growth", Cadence.MONTHLY);
        String busyOwner = unique("lb");
        String busy = approvedSeller(busyOwner, "seller_growth", Cadence.MONTHLY);
        String freshOwner = unique("lf");
        String fresh = approvedSeller(freshOwner, "seller_growth", Cadence.MONTHLY);

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Map<String, Object> body = new HashMap<>(Map.of("name", "Low-sales relief " + quietOwner, "kind", "SELLER",
                "metric", "GROSS_SALES", "threshold", 100_000, "comparison", "LESS_THAN",
                "windowAnchor", "SUBSCRIPTION_START", "withinDaysOfApproval", 30, "rewardType", "FREE_DAYS",
                "freeDays", 30, "eligiblePlans", List.of("seller_growth")));
        body.put("status", "ACTIVE");
        Response created = post(RULES, body);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("/conditionLabel"))
                .isEqualTo("less than $1,000 in sales in the first 30 days of the subscription");
        assertThat(created.body().path("checkAtWindowEnd").asBoolean()).isTrue();
        assertThat(created.body().path("checkLeadHours").asInt()).isEqualTo(24);
        String rule = created.text("/id");
        TestActors.reset();

        // Quiet and busy started their 30-day trial 29 days and 2 hours ago: the check (24 h before the trial ends)
        // is due. Fresh started now.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant trialEnd = now.plus(Duration.ofHours(22));
        for (String org : List.of(quiet, busy)) {
            jdbc.sql("update subscription set started_at = :s, trial_ends_at = :t, status = 'TRIALING' "
                    + "where org_id = :o").param("s", Timestamp.from(trialEnd.minus(Duration.ofDays(30))))
                    .param("t", Timestamp.from(trialEnd)).param("o", org).update();
        }
        Instant soldAt = now.minus(Duration.ofHours(3));
        metrics.shipmentDelivered(UUID.randomUUID(), quiet, 99_999, "USD", soldAt);
        metrics.shipmentDelivered(UUID.randomUUID(), busy, 60_000, "USD", soldAt);
        metrics.shipmentDelivered(UUID.randomUUID(), busy, 40_000, "USD", soldAt);
        assertThat(grants(rule)).as("sales events never decide a rule checked at window end").isEmpty();

        TestActors.as(TestActors.applicant(freshOwner));
        JsonNode pending = progress(fresh, rule);
        assertThat(pending.path("comparison").asString()).isEqualTo("LESS_THAN");
        assertThat(pending.path("pending").asBoolean()).isTrue();
        assertThat(pending.path("qualifies").asBoolean()).isFalse();
        assertThat(pending.path("windowDays").asInt()).isEqualTo(30);
        Instant freshTrialEnd = jdbc.sql("select trial_ends_at from subscription where org_id = :o")
                .param("o", fresh).query(Timestamp.class).single().toInstant();
        assertThat(Instant.parse(pending.path("checkAt").asString()))
                .isEqualTo(freshTrialEnd.minus(Duration.ofHours(24)));

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Map<String, Object> previewBody = new HashMap<>(body);
        previewBody.put("ruleId", rule);
        Response preview = post(RULES + "/preview", previewBody);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().path("pending").asInt()).isPositive();
        assertThat(row(preview, fresh).path("status").asString()).isEqualTo("PENDING");
        assertThat(row(preview, fresh).path("value").asLong()).isZero();
        assertThat(row(preview, quiet).path("status").asString()).isEqualTo("QUALIFIES");
        assertThat(row(preview, busy).path("status").asString()).as("exactly $1,000 is not less than $1,000")
                .isEqualTo("NOT_YET");
        assertThat(row(preview, busy).path("value").asLong()).isEqualTo(100_000);
        TestActors.reset();

        rewards.evaluateWindowEnds();
        List<Map<String, Object>> granted = grants(rule);
        assertThat(granted).hasSize(1);
        Map<String, Object> g = granted.getFirst();
        assertThat(g.get("org_id")).isEqualTo(quiet);
        assertThat(g.get("status")).isEqualTo("APPLIED");
        assertThat(g.get("summary")).isEqualTo("1 month free");
        assertThat(g.get("metric_value")).isEqualTo(99_999L);
        String key = "grant-" + g.get("id") + "-trial";
        String sub = column(quiet, "stripe_subscription_id");
        List<LoggedRequest> calls = trialCalls(sub, key);
        assertThat(calls).hasSize(1);
        assertThat(StripeStub.decoded(calls.getFirst())).contains("trial_end="
                + trialEnd.plus(Duration.ofDays(30)).getEpochSecond(), "proration_behavior=none");

        assertThat(check(rule, quiet)).containsEntry("outcome", "QUALIFIED").containsEntry("value", 99_999L)
                .containsEntry("grant_id", g.get("id"));
        assertThat(check(rule, busy)).containsEntry("outcome", "NOT_QUALIFIED").containsEntry("value", 100_000L);
        assertThat(jdbc.sql("select count(*) from reward_window_check where rule_id = cast(:r as uuid) "
                + "and org_id = :o").param("r", rule).param("o", fresh).query(Long.class).single())
                .as("still in its window").isZero();

        rewards.evaluateWindowEnds();
        assertThat(grants(rule)).as("decided once per (rule, seller, window)").hasSize(1);
        assertThat(trialCalls(sub, key)).hasSize(1);
        assertThat(jdbc.sql("select count(*) from reward_window_check where rule_id = cast(:r as uuid)")
                .param("r", rule).query(Long.class).single()).isEqualTo(2);

        TestActors.as(TestActors.applicant(quietOwner));
        Response quietRewards = get("/v1/orgs/" + quiet + "/billing/rewards");
        JsonNode earned = quietRewards.body().path("earned").valueStream()
                .filter(e -> rule.equals(e.path("ruleId").asString())).findFirst().orElseThrow();
        assertThat(earned.path("ruleComparison").asString()).isEqualTo("LESS_THAN");
        assertThat(earned.path("newTrialEnd").isNull()).isFalse();
        assertThat(quietRewards.body().path("available").valueStream().map(a -> a.path("ruleId").asString()))
                .doesNotContain(rule);
        TestActors.as(TestActors.applicant(busyOwner));
        assertThat(get("/v1/orgs/" + busy + "/billing/rewards").body().path("available").valueStream()
                .map(a -> a.path("ruleId").asString())).as("checked: no longer offered").doesNotContain(rule);

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        post(RULES + "/" + rule + "/status", Map.of("status", "ENDED"));
        TestActors.reset();
    }

    @Test
    void aLessThanRuleNeedsAWindowAndASummedMetric() {
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response bad = post(RULES, Map.of("name", "Quiet listings", "metric", "ACTIVE_LISTINGS", "threshold", 5,
                "comparison", "LESS_THAN", "rewardType", "FREE_DAYS", "freeDays", 30));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().path("fieldErrors").has("metric")).isTrue();
        assertThat(bad.body().path("fieldErrors").has("withinDaysOfApproval")).isTrue();
        Response legacy = post(RULES, Map.of("name", "Legacy", "metric", "GROSS_SALES", "threshold", 100,
                "rewardType", "CREDIT", "creditCents", 100));
        assertThat(legacy.text("/comparison")).as("existing behaviour by default").isEqualTo("AT_LEAST");
        assertThat(legacy.text("/windowAnchor")).isEqualTo("APPROVAL");
        assertThat(legacy.body().path("checkAtWindowEnd").asBoolean()).isFalse();
        TestActors.reset();
    }

    private List<LoggedRequest> trialCalls(String sub, String key) {
        return StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub).stream()
                .filter(r -> key.equals(r.getHeader("Idempotency-Key"))).toList();
    }

    private Map<String, Object> check(String rule, String org) {
        return jdbc.sql("select * from reward_window_check where rule_id = cast(:r as uuid) and org_id = :o")
                .param("r", rule).param("o", org).query().singleRow();
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
