package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S206: daily usage from inventory (FBA storage), content (ads spend) and ai-gateway (AI credits) — WireMock plays
 * the three services and Stripe — aggregated per org and day, reported to Stripe meters idempotently, re-runnable per
 * period, on usage statements and on invoices.
 */
class MeteringIT extends SellerIntegrationTest {

    private static final String METER_EVENTS = "/v1/billing/meter_events";

    @Test
    void usageIsMeteredIdempotentlyAndReRunnablePerPeriod() {
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

        int before = StripeStub.bodies(STRIPE, "POST", METER_EVENTS).size();
        Response first = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.body().path("rowsChanged").asInt()).isEqualTo(5);
        assertThat(first.body().path("reported").asInt()).isEqualTo(4);
        assertThat(first.body().path("pending").asInt()).isEqualTo(1); // the free org has no Stripe customer
        assertThat(first.body().path("status").asString()).isEqualTo("PARTIAL");
        List<String> sent = StripeStub.bodies(STRIPE, "POST", METER_EVENTS);
        assertThat(sent).hasSize(before + 4);
        assertThat(newest(sent, before)).anySatisfy(b -> assertThat(b)
                .contains("event_name=fba_storage_cubic_feet_days", "identifier=" + paid + ":fba_storage:" + d1 + ":r1",
                        "payload[value]=1235"))
                .anySatisfy(b -> assertThat(b).contains("event_name=ads_spend_cents", "payload[value]=450"))
                .anySatisfy(b -> assertThat(b).contains("event_name=ai_credits", "payload[value]=7"))
                .allSatisfy(b -> assertThat(b).doesNotContain(free).doesNotContain("org_unknown_meter"));
        // The paid subscription gets the three metered prices once, so its invoices carry the usage lines.
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/subscription_items").stream()
                .filter(b -> b.contains("metered_lookup_key")).count()).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("select count(*) from metered_subscription_item where org_id = :o").param("o", paid)
                .query(Long.class).single()).isEqualTo(3);

        // Re-running the same period sends nothing new.
        Response again = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(again.body().path("rowsChanged").asInt()).isZero();
        assertThat(again.body().path("reported").asInt()).isZero();
        assertThat(StripeStub.bodies(STRIPE, "POST", METER_EVENTS)).hasSize(before + 4);

        // Late usage: only the difference is reported, under the next identifier revision.
        storage(item("ownerOrg", paid, d1, "cubicFeet", "12.345"), item("ownerOrg", paid, d2, "cubicFeet", "11.5"));
        Response late = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(late.body().path("reported").asInt()).isEqualTo(1);
        assertThat(newest(StripeStub.bodies(STRIPE, "POST", METER_EVENTS), before + 4)).singleElement()
                .satisfies(b -> assertThat(b).contains("identifier=" + paid + ":fba_storage:" + d2 + ":r2",
                        "payload[value]=150"));

        // A source that is down leaves its meter as it was; usage that dropped is flagged for a credit, never negative.
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/ads/spend")).willReturn(WireMock.serverError()));
        storage(item("ownerOrg", paid, d1, "cubicFeet", "12"), item("ownerOrg", paid, d2, "cubicFeet", "11.5"));
        Response partial = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(partial.body().path("failedSources").toString()).contains("ADS");
        assertThat(partial.body().path("corrections").asInt()).isEqualTo(1);
        assertThat(partial.body().path("status").asString()).isEqualTo("PARTIAL");
        assertThat(StripeStub.bodies(STRIPE, "POST", METER_EVENTS)).hasSize(before + 5);
        assertThat(get("/v1/billing/usage/runs").body().size()).isGreaterThanOrEqualTo(4);

        // Usage statement for owner and finance; other organisations' members are refused.
        TestActors.as(TestActors.member(unique("fin"), paid, "seller.finance"));
        Response statement = get("/v1/orgs/" + paid + "/billing/usage?from=" + d1 + "&to=" + d2);
        assertThat(statement.status()).as(statement.body().toString()).isEqualTo(200);
        JsonNode fba = meter(statement.body(), "FBA_STORAGE");
        assertThat(fba.path("quantity").decimalValue()).isEqualByComparingTo("23.50");
        assertThat(fba.path("reportedQuantity").decimalValue()).isEqualByComparingTo("23.85");
        assertThat(fba.path("unit").asString()).isEqualTo("cubic-foot-day");
        assertThat(fba.path("days").get(0).path("status").asString()).isEqualTo("CORRECTION");
        JsonNode adsLine = meter(statement.body(), "ADS_SPEND");
        assertThat(adsLine.path("quantity").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(adsLine.path("estimatedAmountCents").asLong()).isEqualTo(450);
        assertThat(meter(statement.body(), "AI_CREDITS").path("estimatedAmountCents").asLong()).isEqualTo(7);
        assertThat(statement.body().path("estimatedTotalCents").asLong()).isPositive();
        TestActors.as(TestActors.applicant(paidOwner));
        assertThat(get("/v1/orgs/" + paid + "/billing/usage").status()).isEqualTo(200);
        TestActors.as(TestActors.member(unique("mkt"), paid, "seller.marketing"));
        assertThat(get("/v1/orgs/" + paid + "/billing/usage").status()).isEqualTo(403);
        TestActors.as(TestActors.applicant(freeOwner));
        Response freeStatement = get("/v1/orgs/" + free + "/billing/usage?from=" + d1 + "&to=" + d2);
        assertThat(meter(freeStatement.body(), "AI_CREDITS").path("days").get(0).path("status").asString())
                .isEqualTo("PENDING");
    }

    /**
     * AfroTransact Retail is the merchant of record, not a marketplace seller: even when seller-service knows the org
     * and it has a Stripe customer, its storage, ads and AI usage is never stored, metered or reported; it can't be
     * put on a plan and can't be suspended.
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
                insert into subscription (org_id, plan_code, cadence, status, stripe_customer_id, updated_at)
                values (:id, 'seller_growth', 'MONTHLY', 'ACTIVE', 'cus_retail_meter_it', now())
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
        int before = StripeStub.bodies(STRIPE, "POST", METER_EVENTS).size();
        Response run = post("/v1/billing/usage/runs", Map.of("from", d1.toString(), "to", d2.toString()));
        assertThat(run.status()).as(run.body().toString()).isEqualTo(200);
        assertThat(run.body().path("rowsChanged").asInt()).isEqualTo(3);
        assertThat(run.body().path("reported").asInt()).isEqualTo(3);
        assertThat(run.body().path("pending").asInt()).isZero();
        List<String> sent = newest(StripeStub.bodies(STRIPE, "POST", METER_EVENTS), before);
        assertThat(sent).hasSize(3).allSatisfy(b -> assertThat(b).contains("identifier=" + paid)
                .doesNotContain(retail).doesNotContain("cus_retail_meter_it"));
        assertThat(jdbc.sql("select count(*) from usage_daily where org_id = :o").param("o", retail)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from metered_subscription_item where org_id = :o").param("o", retail)
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

    @Test
    void invoicesShowMeteredLines() {
        String owner = unique("meter-inv");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String customer = jdbc.sql("select stripe_customer_id from subscription where org_id = :o").param("o", org)
                .query(String.class).single();
        String invoiceId = "in_" + unique("m").replace("-", "");
        long now = System.currentTimeMillis() / 1000;
        String invoice = StripeEvents.invoice(invoiceId, customer, "paid", 3900 + 1235, 1, null)
                .replace(",\"created\":", ",\"lines\":{\"object\":\"list\",\"data\":["
                        + "{\"id\":\"il_1\",\"object\":\"line_item\",\"description\":\"Seller Growth\",\"amount\":3900,"
                        + "\"quantity\":1,\"price\":{\"id\":\"price_p\",\"lookup_key\":\"seller_growth_monthly\"}},"
                        + "{\"id\":\"il_2\",\"object\":\"line_item\",\"description\":\"FBA storage\",\"amount\":1235,"
                        + "\"quantity\":4258,\"price\":{\"id\":\"price_m\",\"lookup_key\":\"metered_fba_storage\"},"
                        + "\"period\":{\"start\":" + (now - 86400) + ",\"end\":" + now + "}}]},\"created\":");
        String event = StripeEvents.event(unique("evt"), "invoice.paid", now, null, invoice);
        assertThat(webhook("/v1/webhooks/stripe-billing", event, sign(event, BILLING_SECRET)).text("/outcome"))
                .isEqualTo("applied");

        TestActors.as(TestActors.applicant(owner));
        JsonNode inv = get("/v1/orgs/" + org + "/billing/invoices").body().get(0);
        assertThat(inv.path("lines").size()).isEqualTo(2);
        assertThat(inv.path("lines").get(1).path("metered").asBoolean()).isTrue();
        assertThat(inv.path("lines").get(1).path("meter").asString()).isEqualTo("FBA_STORAGE");
        assertThat(inv.path("lines").get(0).path("metered").asBoolean()).isFalse();
        assertThat(inv.path("meteredAmountCents").asLong()).isEqualTo(1235);
    }

    /** WireMock lists requests newest first: the ones after the first {@code before}. */
    private static List<String> newest(List<String> all, int before) {
        return all.subList(0, all.size() - before);
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
