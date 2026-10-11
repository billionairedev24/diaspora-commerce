package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S292–S294: the plan catalogue is ours. Staff create, version, publish and retire plans; publishing syncs Stripe
 * (Products and Prices, adopted from make stripe-setup when they match, never edited: a new Price per version and the
 * old one archived); subscribers stay on their version until a staff migration; invite-only plans; drift report.
 */
class PlanCatalogIT extends SellerIntegrationTest {

    private static final String PLANS = "/v1/billing/plans";

    @Test
    void createPublishAdoptVersionMigrateAndRetire() {
        String code = "seller_t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        TestActors.as(TestActors.applicant(unique("nobody")));
        assertThat(post(PLANS, plan(code, "PUBLIC")).status()).as("customers can't edit plans").isEqualTo(403);
        TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
        assertThat(post(PLANS, plan(code, "PUBLIC")).status()).as("seller:billing reads only").isEqualTo(403);

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response bad = post(PLANS, Map.of("code", "Bad Code", "kind", "SELLER", "name", ""));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.text("/code")).isEqualTo("seller.invalid-plan");
        Response created = post(PLANS, plan(code, "PUBLIC"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("/status")).isEqualTo("DRAFT");
        assertThat(created.text("/versions/0/status")).isEqualTo("DRAFT");
        assertThat(get("/v1/plans?kind=SELLER").body().valueStream().map(p -> p.path("code").asString()))
                .as("a draft is not offered").doesNotContain(code);

        // make stripe-setup already made the monthly Price under the plan's lookup key: it is adopted, not duplicated.
        String adopted = "price_adopt" + code.substring(8);
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/prices"))
                .withQueryParam("lookup_keys[0]", WireMock.equalTo(code + "_monthly")).atPriority(1)
                .willReturn(WireMock.okJson("{\"object\":\"list\",\"has_more\":false,\"url\":\"/v1/prices\",\"data\":[{"
                        + "\"id\":\"" + adopted + "\",\"object\":\"price\",\"active\":true,\"currency\":\"usd\","
                        + "\"unit_amount\":2500,\"product\":\"prod_adopt" + code.substring(8) + "\",\"lookup_key\":\""
                        + code + "_monthly\",\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}]}")));
        Response published = post(PLANS + "/" + code + "/publish", Map.of());
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.text("/status")).isEqualTo("ACTIVE");
        assertThat(published.body().path("currentVersion").asInt()).isEqualTo(1);
        assertThat(price(published, 1, "MONTHLY")).isEqualTo(adopted);
        assertThat(published.text("/stripeProductId")).isEqualTo("prod_adopt" + code.substring(8));
        List<LoggedRequest> created1 = priceCreations(code + "_quarterly");
        assertThat(created1).hasSize(1);
        assertThat(created1.getFirst().getHeader("Idempotency-Key")).isEqualTo("plan-price-" + code
                + "-v1-quarterly-7125");
        assertThat(StripeStub.decoded(created1.getFirst())).contains("product=prod_adopt" + code.substring(8),
                "currency=usd", "unit_amount=7125", "recurring[interval]=month", "recurring[interval_count]=3",
                "lookup_key=" + code + "_quarterly", "transfer_lookup_key=true", "metadata[plan_code]=" + code,
                "metadata[plan_version]=1", "metadata[cadence]=QUARTERLY");
        assertThat(priceCreations(code + "_monthly")).as("adopted, not created").isEmpty();
        assertThat(StripeStub.decoded(priceCreations(code + "_annual").getFirst())).contains("unit_amount=25000",
                "recurring[interval_count]=12");

        // A seller subscribes to v1.
        String owner = unique("cat");
        String org = approvedSeller(owner, code, Cadence.MONTHLY);
        assertThat(column(org, "plan_version")).isEqualTo("1");
        String sub = column(org, "stripe_subscription_id");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/subscriptions")).anySatisfy(b -> assertThat(b)
                .contains("items[0][price]=" + adopted, "metadata[plan_code]=" + code, "trial_end="));

        // New prices: a draft version 2; the subscriber stays on v1 until migrated.
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response unchanged = put(PLANS + "/" + code + "/draft", Map.of("monthlyCents", 2500, "quarterlyCents", 7125,
                "annualCents", 25000, "trialDays", 14));
        assertThat(unchanged.text("/code")).isEqualTo("seller.plan-unchanged");
        Response draft = put(PLANS + "/" + code + "/draft", Map.of("monthlyCents", 3000, "quarterlyCents", 8550,
                "annualCents", 30000, "trialDays", 14, "note", "2027 prices"));
        assertThat(draft.status()).isEqualTo(200);
        assertThat(draft.text("/versions/0/status")).isEqualTo("DRAFT");
        assertThat(draft.body().path("versions").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(draft.body().path("currentVersion").asInt()).as("still v1 until published").isEqualTo(1);
        Response v2 = post(PLANS + "/" + code + "/publish", Map.of());
        assertThat(v2.body().path("currentVersion").asInt()).isEqualTo(2);
        String newMonthly = price(v2, 2, "MONTHLY");
        assertThat(newMonthly).isNotEqualTo(adopted);
        List<LoggedRequest> monthly2 = priceCreations(code + "_monthly");
        assertThat(monthly2).hasSize(1);
        assertThat(monthly2.getFirst().getHeader("Idempotency-Key")).isEqualTo("plan-price-" + code
                + "-v2-monthly-3000-" + adopted);
        assertThat(StripeStub.decoded(monthly2.getFirst())).contains("unit_amount=3000", "transfer_lookup_key=true",
                "metadata[plan_version]=2");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/prices/" + adopted)).as("the old Price is archived, never "
                + "edited").containsExactly("active=false");
        assertThat(v2.body().path("versions").get(1).path("subscribers").asLong()).as("v1 keeps its subscriber")
                .isEqualTo(1);

        TestActors.as(TestActors.applicant(owner));
        Response billing = get("/v1/orgs/" + org + "/billing");
        assertThat(billing.body().path("planVersion").asInt()).isEqualTo(1);
        assertThat(billing.text("/price/perMonthLabel")).as("old version, old price").isEqualTo("$25/mo");

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response preview = get(PLANS + "/" + code + "/migration");
        assertThat(preview.body().path("toVersion").asInt()).isEqualTo(2);
        JsonNode row = preview.body().path("rows").get(0);
        assertThat(row.path("orgId").asString()).isEqualTo(org);
        assertThat(row.path("fromCents").asLong()).isEqualTo(2500);
        assertThat(row.path("toCents").asLong()).isEqualTo(3000);
        Response migrated = post(PLANS + "/" + code + "/migration", Map.of("orgIds", List.of(org)));
        assertThat(migrated.body().path("migrated").get(0).asString()).isEqualTo(org);
        List<LoggedRequest> swaps = StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub).stream()
                .filter(r -> ("migrate-" + org + "-" + code + "-v2").equals(r.getHeader("Idempotency-Key"))).toList();
        assertThat(swaps).hasSize(1);
        assertThat(StripeStub.decoded(swaps.getFirst())).contains("items[0][price]=" + newMonthly,
                "proration_behavior=none", "metadata[plan_version]=2");
        assertThat(column(org, "plan_version")).isEqualTo("2");
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString()).isEqualTo("MIGRATED");
        assertThat(get(PLANS + "/" + code + "/migration").body().path("rows").size()).isZero();

        // Retired: no new subscribers, the current one still sees it.
        assertThat(post(PLANS + "/" + code + "/retire", Map.of()).text("/status")).isEqualTo("RETIRED");
        assertThat(get("/v1/plans?kind=SELLER").body().valueStream().map(p -> p.path("code").asString()))
                .doesNotContain(code);
        TestActors.as(TestActors.applicant(owner));
        assertThat(get("/v1/orgs/" + org + "/billing/plans").body().valueStream().map(p -> p.path("code").asString()))
                .contains(code, "seller_growth");
    }

    @Test
    void inviteOnlyPlansAndTheDriftReport() {
        String code = "seller_i" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String owner = unique("inv");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        post(PLANS, plan(code, "INVITE_ONLY"));
        Response published = post(PLANS + "/" + code + "/publish", Map.of());
        assertThat(published.text("/visibility")).isEqualTo("INVITE_ONLY");
        assertThat(get("/v1/plans").body().valueStream().map(p -> p.path("code").asString())).doesNotContain(code);

        TestActors.as(TestActors.applicant(owner));
        assertThat(codes(get("/v1/orgs/" + org + "/billing/plans"))).doesNotContain(code);
        assertThat(post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", code)).text("/code"))
                .isEqualTo("seller.unknown-plan");
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response invited = put(PLANS + "/" + code + "/invites", Map.of("orgIds", List.of(org, "org_unknown")));
        assertThat(invited.body().path("invites").valueStream().map(JsonNode::asString)).containsExactly(org);
        TestActors.as(TestActors.applicant(owner));
        assertThat(codes(get("/v1/orgs/" + org + "/billing/plans"))).contains(code);

        // Metadata edits keep the version and use optimistic locking.
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        long rowVersion = get(PLANS + "/" + code).body().path("rowVersion").asLong();
        Response renamed = put(PLANS + "/" + code, Map.of("name", "Growth Partner", "features", List.of("Priority"),
                "visibility", "INVITE_ONLY", "sortOrder", 950, "expectedVersion", rowVersion));
        assertThat(renamed.text("/name")).isEqualTo("Growth Partner");
        assertThat(renamed.body().path("currentVersion").asInt()).isEqualTo(1);
        assertThat(put(PLANS + "/" + code, Map.of("name", "Stale", "expectedVersion", rowVersion)).status())
                .isEqualTo(409);

        // Drift: the stored Price of this plan's monthly cadence matches Stripe; one was deleted at Stripe.
        String monthly = price(published, 1, "MONTHLY");
        String annual = price(published, 1, "ANNUAL");
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/prices/" + monthly)).atPriority(1)
                .willReturn(WireMock.okJson("{\"id\":\"" + monthly + "\",\"object\":\"price\",\"active\":true,"
                        + "\"currency\":\"usd\",\"unit_amount\":2500,\"recurring\":{\"interval\":\"month\","
                        + "\"interval_count\":1}}")));
        TestActors.as(TestActors.staff(unique("billing"), SellerPermissions.BILLING));
        Response report = get("/v1/billing/stripe-sync");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().path("applied").asBoolean()).isFalse();
        assertThat(line(report, code, "MONTHLY").path("state").asString()).isEqualTo("IN_SYNC");
        assertThat(line(report, code, "ANNUAL").path("state").asString()).isEqualTo("DRIFT");
        assertThat(line(report, "seller_essentials", "MONTHLY").path("state").asString()).isEqualTo("FREE");
        assertThat(post("/v1/billing/stripe-sync", Map.of()).status()).as("syncing needs plans:manage")
                .isEqualTo(403);
        int before = StripeStub.requests(STRIPE, "POST", "/v1/prices").size();
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response synced = post("/v1/billing/stripe-sync", Map.of());
        assertThat(synced.body().path("applied").asBoolean()).isTrue();
        assertThat(line(synced, code, "MONTHLY").path("action").asString()).isEqualTo("NONE");
        JsonNode replaced = line(synced, code, "ANNUAL");
        assertThat(replaced.path("action").asString()).isEqualTo("CREATED");
        assertThat(replaced.path("stripePriceId").asString()).isNotEqualTo(annual);
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/prices").size()).isGreaterThan(before);
    }

    private List<LoggedRequest> priceCreations(String lookupKey) {
        return StripeStub.requests(STRIPE, "POST", "/v1/prices").stream()
                .filter(r -> StripeStub.decoded(r).contains("lookup_key=" + lookupKey + "&")
                        || StripeStub.decoded(r).endsWith("lookup_key=" + lookupKey)).toList();
    }

    private static JsonNode line(Response report, String plan, String cadence) {
        return report.body().path("lines").valueStream().filter(l -> l.path("planCode").asString().equals(plan)
                && l.path("cadence").asString().equals(cadence)).findFirst().orElseThrow();
    }

    private static List<String> codes(Response plans) {
        return plans.body().valueStream().map(p -> p.path("code").asString()).toList();
    }

    private static String price(Response plan, int version, String cadence) {
        return plan.body().path("versions").valueStream().filter(v -> v.path("version").asInt() == version)
                .findFirst().orElseThrow().path("prices").valueStream()
                .filter(p -> p.path("cadence").asString().equals(cadence)).findFirst().orElseThrow()
                .path("stripePriceId").asString();
    }

    private static Map<String, Object> plan(String code, String visibility) {
        return Map.of("code", code, "kind", "SELLER", "name", "Test " + code, "tagline", "For tests",
                "features", List.of("Up to 10 listings"), "visibility", visibility, "sortOrder", 900,
                "prices", Map.of("monthlyCents", 2500, "quarterlyCents", 7125, "annualCents", 25000,
                        "trialDays", 14));
    }

    private String column(String org, String column) {
        return jdbc.sql("select " + column + "::text from subscription where org_id = :o").param("o", org)
                .query(String.class).optional().orElse(null);
    }
}
