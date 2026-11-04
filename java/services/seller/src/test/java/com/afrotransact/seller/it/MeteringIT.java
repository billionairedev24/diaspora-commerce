package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.UsageMeter;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S206: daily usage from inventory (FBA storage), content (ads spend) and ai-gateway (AI credits) — WireMock plays
 * the three services — aggregated per org and day, re-runnable per period, on usage statements, and billed on the
 * seller's next plan invoice (usage that settled is billed once; usage that dropped afterwards is credited).
 */
class MeteringIT extends SellerIntegrationTest {

    private BillingService billing;

    @BeforeEach
    void wire(@Autowired BillingService billingService) {
        this.billing = billingService;
    }

    @Test
    void usageIsMeteredIdempotentlyReRunnablePerPeriodAndBilledOnTheNextInvoice() {
        String paidOwner = unique("meter-paid");
        String paid = approvedSeller(paidOwner, "seller_growth", Cadence.MONTHLY);
        String freeOwner = unique("meter-free");
        String free = approvedSeller(freeOwner, "seller_essentials", Cadence.MONTHLY);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate d1 = today.minusDays(2);
        LocalDate d2 = today.minusDays(1);

        storage(item("ownerOrg", paid, d1, "cubicFeet", "12.345"), item("ownerOrg", paid, d2, "cubicFeet", "10"),
                item("ownerOrg", "org_unknown_meter", d2, "cubicFeet", "99"));
        source("/v1/ads/spend", item("sellerOrgId", paid, d2, "spendMinor", "450", "currency", "\"USD\""),
                item("sellerOrgId", paid, d2, "spendMinor", "999", "currency", "\"EUR\""));
        source("/v1/ai/usage/orgs", item("orgId", paid, d1, "credits", "7"), item("orgId", free, d2, "credits", "3"));

        // Sellers can't run metering; staff need billing:usage.
        TestActors.as(TestActors.applicant(paidOwner));
        assertThat(post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString())).status())
                .isEqualTo(403);
        TestActors.as(TestActors.staff("finance-ops", SellerPermissions.USAGE));
        assertThat(post("/v1/billing/usage/runs", Map.of("from", d2.toString(), "to", today.plusDays(1).toString()))
                .status()).isEqualTo(422);

        Response first = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.body().path("rowsChanged").asInt()).isEqualTo(5);
        assertThat(first.body().path("pending").asInt()).as("waiting for the next plan invoice").isEqualTo(5);
        assertThat(first.body().path("status").asString()).isEqualTo("COMPLETED");

        // Re-running the same period changes nothing.
        Response again = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(again.body().path("rowsChanged").asInt()).isZero();
        assertThat(again.body().path("pending").asInt()).isEqualTo(5);

        // A source that is down leaves its meter as it was.
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/ads/spend")).willReturn(WireMock.serverError()));
        Response partial = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(partial.body().path("failedSources").toString()).contains("ADS");
        assertThat(partial.body().path("status").asString()).isEqualTo("PARTIAL");
        source("/v1/ads/spend", item("sellerOrgId", paid, d2, "spendMinor", "450", "currency", "\"USD\""));

        // Usage statement for owner and finance; other organisations' members are refused.
        TestActors.as(TestActors.member(unique("fin"), paid, "seller.finance"));
        Response statement = get("/v1/orgs/" + paid + "/billing/usage?from=" + d1 + "&to=" + d2);
        assertThat(statement.status()).as(statement.body().toString()).isEqualTo(200);
        JsonNode fba = meter(statement.body(), "FBA_STORAGE");
        assertThat(fba.path("quantity").decimalValue()).isEqualByComparingTo("22.35");
        assertThat(fba.path("reportedQuantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(fba.path("unit").asString()).isEqualTo("cubic-foot-day");
        assertThat(fba.path("days").get(0).path("status").asString()).isEqualTo("PENDING");
        JsonNode adsLine = meter(statement.body(), "ADS_SPEND");
        assertThat(adsLine.path("quantity").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(adsLine.path("estimatedAmountCents").asLong()).isEqualTo(450);
        assertThat(meter(statement.body(), "AI_CREDITS").path("estimatedAmountCents").asLong()).isEqualTo(7);
        TestActors.as(TestActors.member(unique("mkt"), paid, "seller.marketing"));
        assertThat(get("/v1/orgs/" + paid + "/billing/usage").status()).isEqualTo(403);

        // The next plan invoice bills the settled days (up to two days ago) once, as metered lines.
        jdbc.sql("update subscription set trial_ends_at = null, current_period_end = now() - interval '1 hour', "
                + "anchor_date = now() - interval '1 hour' where org_id = :o").param("o", paid).update();
        billing.renewDueSubscriptions();
        TestActors.as(TestActors.applicant(paidOwner));
        JsonNode invoice = get("/v1/orgs/" + paid + "/billing/invoices").body().get(0);
        assertThat(invoice.path("lines").valueStream().map(l -> l.path("meter").asString(null)).filter(m -> m != null))
                .containsExactlyInAnyOrder("FBA_STORAGE", "AI_CREDITS");
        long expectedUsage = UsageMeter.FBA_STORAGE.estimatedCents(1235) + UsageMeter.AI_CREDITS.estimatedCents(7);
        assertThat(invoice.path("meteredAmountCents").asLong()).isEqualTo(expectedUsage);
        assertThat(invoice.path("amountDueCents").asLong()).isEqualTo(3900 + expectedUsage);
        assertThat(jdbc.sql("select reported_quantity from usage_daily where org_id = :o and meter = 'FBA_STORAGE' "
                + "and day = :d").param("o", paid).param("d", Date.valueOf(d1)).query(Long.class).single())
                .isEqualTo(1235);
        assertThat(jdbc.sql("select reported_quantity from usage_daily where org_id = :o and meter = 'FBA_STORAGE' "
                + "and day = :d").param("o", paid).param("d", Date.valueOf(d2)).query(Long.class).single())
                .as("yesterday has not settled yet").isZero();

        // Usage that dropped after it was billed is flagged for a credit, never a negative meter.
        storage(item("ownerOrg", paid, d1, "cubicFeet", "12"), item("ownerOrg", paid, d2, "cubicFeet", "10"));
        TestActors.as(TestActors.staff("finance-ops", SellerPermissions.USAGE));
        Response dropped = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(dropped.body().path("corrections").asInt()).isEqualTo(1);
        assertThat(get("/v1/billing/usage/runs").body().size()).isGreaterThanOrEqualTo(4);
        TestActors.as(TestActors.applicant(freeOwner));
        Response freeStatement = get("/v1/orgs/" + free + "/billing/usage?from=" + d1 + "&to=" + d2);
        assertThat(meter(freeStatement.body(), "AI_CREDITS").path("days").get(0).path("status").asString())
                .isEqualTo("PENDING");
    }

    /**
     * AfroTransact Retail is the merchant of record, not a marketplace seller: even when seller-service knows the org,
     * its storage, ads and AI usage is never stored or billed; it can't be put on a plan and can't be suspended.
     */
    @Test
    void afroTransactRetailIsNeverMetered() {
        String owner = unique("meter-r");
        String paid = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String retail = "org_afrotransact_retail";
        jdbc.sql("""
                insert into organisation (id, kind, legal_name, display_name, country, status, created_at, updated_at)
                values (:id, 'SELLER', 'AfroTransact Retail', 'AfroTransact', 'US', 'APPROVED', now(), now())
                on conflict (id) do nothing
                """).param("id", retail).update();
        jdbc.sql("""
                insert into subscription (org_id, plan_code, cadence, status, updated_at)
                values (:id, 'seller_growth', 'MONTHLY', 'ACTIVE', now())
                on conflict (org_id) do nothing
                """).param("id", retail).update();
        // Days of their own, so the other test's runs never touch these rows.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate d1 = today.minusDays(5);
        LocalDate d2 = today.minusDays(4);
        storage(item("ownerOrg", retail, d1, "cubicFeet", "500"), item("ownerOrg", paid, d2, "cubicFeet", "2"));
        source("/v1/ads/spend", item("sellerOrgId", retail, d2, "spendMinor", "90000", "currency", "\"USD\""),
                item("sellerOrgId", paid, d2, "spendMinor", "120", "currency", "\"USD\""));
        source("/v1/ai/usage/orgs", item("orgId", retail, d1, "credits", "4000"), item("orgId", paid, d1, "credits",
                "5"));

        TestActors.as(TestActors.staff("finance-ops", SellerPermissions.USAGE));
        Response run = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(run.status()).as(run.body().toString()).isEqualTo(200);
        assertThat(run.body().path("rowsChanged").asInt()).isEqualTo(3);
        assertThat(run.body().path("pending").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from usage_daily where org_id = :o").param("o", retail)
                .query(Long.class).single()).isZero();
        JsonNode statement = get("/v1/orgs/" + retail + "/billing/usage?from=" + d1 + "&to=" + d2).body();
        assertThat(statement.path("estimatedTotalCents").asLong()).isZero();

        // No plan or billing for Retail, and Trust and Safety can't suspend it or pause its payouts.
        TestActors.as(TestActors.member(unique("r-owner"), retail, "seller.owner"));
        Response plan = post("/v1/orgs/" + retail + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "MONTHLY"));
        assertThat(plan.status()).as(plan.body().toString()).isEqualTo(422);
        assertThat(plan.text("/code")).isEqualTo("seller.retail-not-billed");
        TestActors.as(TestActors.service("trust-ops-service", SellerPermissions.SELLERS_ENFORCE));
        Response suspend = post("/v1/orgs/" + retail + "/suspend", Map.of("reasonCode", "COUNTERFEIT",
                "note", "test"));
        assertThat(suspend.status()).as(suspend.body().toString()).isEqualTo(422);
        assertThat(suspend.text("/code")).isEqualTo("seller.retail-exempt");
        assertThat(jdbc.sql("select status from organisation where id = :o").param("o", retail).query(String.class)
                .single()).isEqualTo("APPROVED");
    }

    private static JsonNode meter(JsonNode statement, String meter) {
        for (JsonNode m : statement.path("meters")) {
            if (m.path("meter").asString().equals(meter)) {
                return m;
            }
        }
        throw new AssertionError("no meter " + meter + " in " + statement);
    }

    private static String item(String orgField, String org, LocalDate day, String valueField, String value,
                               String... extra) {
        StringBuilder b = new StringBuilder("{\"" + orgField + "\":\"" + org + "\",\"day\":\"" + day + "\",\""
                + valueField + "\":" + value);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            b.append(",\"").append(extra[i]).append("\":").append(extra[i + 1]);
        }
        return b.append('}').toString();
    }

    private static void storage(String... items) {
        source("/v1/storage/usage", items);
    }

    private static void source(String path, String... items) {
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"items\":[" + String.join(",", items) + "]}")));
    }
}
