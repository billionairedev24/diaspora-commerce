package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.application.EntitlementsService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S292–S294: the plan catalogue is ours and so is the billing. Staff create, version, publish and retire plans (a new
 * version for new prices, never edited in place); subscribers stay on their version until a staff migration;
 * invite-only plans. Nothing is synced to Stripe: periods and invoices are ours.
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

        Response published = post(PLANS + "/" + code + "/publish", Map.of());
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.text("/status")).isEqualTo("ACTIVE");
        assertThat(published.body().path("currentVersion").asInt()).isEqualTo(1);
        assertThat(price(published, 1, "MONTHLY")).isEqualTo(2500);
        assertThat(price(published, 1, "QUARTERLY")).isEqualTo(7125);
        assertThat(price(published, 1, "ANNUAL")).isEqualTo(25000);

        // A seller subscribes to v1.
        String owner = unique("cat");
        String org = approvedSeller(owner, code, Cadence.MONTHLY);
        assertThat(column(org, "plan_version")).isEqualTo("1");
        assertThat(column(org, "status")).isEqualTo("TRIALING");

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
        assertThat(price(v2, 2, "MONTHLY")).isEqualTo(3000);
        assertThat(price(v2, 1, "MONTHLY")).as("version 1 keeps its price").isEqualTo(2500);
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
    void inviteOnlyPlansAndMetadataEdits() {
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
    }

    @Test
    void entitlementsAreConfiguredPerPlanAndReadByMembersAndServices() {
        String code = "seller_e" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Map<String, Object> body = new HashMap<>(plan(code, "PUBLIC"));
        body.put("limits", Map.of("listings", "lots"));
        Response bad = post(PLANS, body);
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().path("fieldErrors").has("limits.listings")).isTrue();
        body.put("limits", Map.of("listings", "2", "aiListingWriter", "true", "lolaTier", "GROWTH",
                "bulkUpload", "true", "support", "Priority email"));
        Response created = post(PLANS, body);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().at("/entitlements/bulkUpload").asBoolean()).isTrue();
        assertThat(created.body().at("/entitlements/coupons").asBoolean()).as("off unless switched on").isFalse();
        assertThat(created.body().path("features").toString()).as("features list what the plan allows, not typed text")
                .contains("Up to 2 listings", "Bulk upload", "AI listing writer", "Lola Growth assistant")
                .doesNotContain("Coupons", "Up to 10 listings");
        assertThat(created.body().at("/entitlements/listings").asLong()).isEqualTo(2);
        assertThat(created.body().at("/entitlements/aiListingWriter").asBoolean()).isTrue();
        assertThat(created.text("/entitlements/lolaTier")).isEqualTo("GROWTH");
        post(PLANS + "/" + code + "/publish", Map.of());
        assertThat(get("/v1/plans?kind=SELLER").body().valueStream().filter(p -> code.equals(p.path("code")
                .asString())).findFirst().orElseThrow().at("/entitlements/listings").asLong()).as("public").isEqualTo(2);

        // Renaming the plan without sending limits keeps the entitlements; sending them replaces them.
        long rowVersion = get(PLANS + "/" + code).body().path("rowVersion").asLong();
        Response renamed = put(PLANS + "/" + code, Map.of("name", "Renamed", "expectedVersion", rowVersion));
        assertThat(renamed.body().at("/entitlements/listings").asLong()).isEqualTo(2);
        Response unlimited = put(PLANS + "/" + code, Map.of("name", "Renamed", "limits", Map.of("listings",
                "unlimited", "lolaTier", "PRO"), "expectedVersion", renamed.body().path("rowVersion").asLong()));
        assertThat(unlimited.body().at("/entitlements/listingsUnlimited").asBoolean()).isTrue();
        assertThat(unlimited.text("/entitlements/lolaTier")).isEqualTo("PRO");
        put(PLANS + "/" + code, Map.of("name", "Renamed", "limits", Map.of("listings", "2", "aiListingWriter",
                "true", "lolaTier", "GROWTH"), "expectedVersion", unlimited.body().path("rowVersion").asLong()));

        String owner = unique("ent");
        String org = approvedSeller(owner, code, Cadence.MONTHLY);
        TestActors.as(TestActors.applicant(owner));
        Response mine = get("/v1/orgs/" + org + "/entitlements");
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.text("/planCode")).isEqualTo(code);
        assertThat(mine.body().at("/entitlements/listings").asLong()).isEqualTo(2);
        assertThat(get("/v1/orgs/" + org + "/billing").body().at("/entitlements/lolaTier").asString())
                .isEqualTo("GROWTH");
        TestActors.as(TestActors.member(unique("mkt"), org, "seller.marketing"));
        assertThat(get("/v1/orgs/" + org + "/entitlements").status()).as("every member sees the plan").isEqualTo(200);
        TestActors.as(TestActors.applicant(unique("stranger")));
        assertThat(get("/v1/orgs/" + org + "/entitlements").status()).isEqualTo(403);
        TestActors.as(TestActors.service("offers-service", EntitlementsService.SERVICE_READ));
        assertThat(get("/v1/orgs/" + org + "/entitlements").body().at("/entitlements/listings").asLong())
                .isEqualTo(2);
        TestActors.as(TestActors.service("orders-service", "orders:manage"));
        assertThat(get("/v1/orgs/" + org + "/entitlements").status()).as("another service without the scope")
                .isEqualTo(403);
    }

    private static List<String> codes(Response plans) {
        return plans.body().valueStream().map(p -> p.path("code").asString()).toList();
    }

    private static long price(Response plan, int version, String cadence) {
        return plan.body().path("versions").valueStream().filter(v -> v.path("version").asInt() == version)
                .findFirst().orElseThrow().path("prices").valueStream()
                .filter(p -> p.path("cadence").asString().equals(cadence)).findFirst().orElseThrow()
                .path("amountCents").asLong();
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
