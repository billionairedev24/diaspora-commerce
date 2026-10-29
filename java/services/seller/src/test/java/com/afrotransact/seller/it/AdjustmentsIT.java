package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S295 and S297: trial campaigns (promo codes, sign-up windows), the per-seller trial override, and staff adjustments
 * (credit, free days and months, waived invoice) with reason codes and request idempotency; the per-seller billing
 * panel. Everything is applied locally: there is no Stripe call behind a grant.
 */
class AdjustmentsIT extends SellerIntegrationTest {

    private BillingService renewals;

    @BeforeEach
    void wire(@Autowired BillingService billingService) {
        this.renewals = billingService;
    }

    @Test
    void aPromoCodeCampaignSetsTheTrialEndAndCountsTheRedemption() {
        String code = "GROW60" + unique("x").substring(2).toUpperCase().replace("-", "");
        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        Response bad = post("/v1/billing/trial-campaigns", Map.of("name", "No code no window", "trialDays", 60));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().path("fieldErrors").has("signupFrom")).isTrue();
        Response campaign = post("/v1/billing/trial-campaigns", Map.of("name", "Growth 60", "promoCode", code,
                "trialDays", 60, "eligiblePlans", List.of("seller_growth"), "maxRedemptions", 5));
        assertThat(campaign.status()).isEqualTo(201);
        assertThat(campaign.text("/status")).isEqualTo("ACTIVE");
        assertThat(post("/v1/billing/trial-campaigns", Map.of("name", "Dup", "promoCode", code, "trialDays", 10))
                .body().path("fieldErrors").path("promoCode").asString()).isEqualTo("Already used");

        String owner = unique("promo");
        Instant before = Instant.now();
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, code.toLowerCase());
        Instant trialEnd = Instant.parse(column(org, "trial_ends_at").replace(' ', 'T').replaceAll("\\+00$", "Z"));
        assertThat(trialEnd).as("60 days, not the plan's 30").isBetween(before.plus(Duration.ofDays(60))
                .minusSeconds(5), Instant.now().plus(Duration.ofDays(60)).plusSeconds(5));
        assertThat(column(org, "trial_source")).isEqualTo("CAMPAIGN");
        TestActors.as(TestActors.staff(unique("billing"), SellerPermissions.BILLING));
        JsonNode stored = get("/v1/billing/trial-campaigns").body().valueStream()
                .filter(c -> c.path("promoCode").asString().equals(code)).findFirst().orElseThrow();
        assertThat(stored.path("redemptions").asInt()).isEqualTo(1);
    }

    @Test
    void anUnknownPromoCodeIsRefusedOnThePlanStep() {
        String user = unique("nopromo");
        TestActors.as(TestActors.applicant(user));
        post("/v1/applications/me", Map.of("kind", "SELLER"));
        Response r = put("/v1/applications/me/steps/plan", Map.of("planCode", "seller_growth", "cadence", "MONTHLY",
                "promoCode", "NOPE123"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("/code")).isEqualTo("seller.invalid-promo-code");
        assertThat(r.body().path("fieldErrors").has("promoCode")).isTrue();
    }

    @Test
    void staffAdjustmentsAreAuditedIdempotentAndAppliedLocally() {
        String owner = unique("adj");
        String org = approvedSeller(owner, "seller_pro", Cadence.MONTHLY);
        String credits = "/v1/orgs/" + org + "/billing/credits";

        TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
        assertThat(post(credits, Map.of("amountCents", 2500, "reasonCode", "GOODWILL")).status()).isEqualTo(403);
        TestActors.as(TestActors.staff(unique("finance"), SellerPermissions.BILLING_ADJUST));
        assertThat(post(credits, Map.of("amountCents", 2500, "reasonCode", "OTHER")).body().path("fieldErrors")
                .has("note")).as("OTHER needs a note").isTrue();
        assertThat(post(credits, Map.of("amountCents", 2500, "reasonCode", "NOPE")).status()).isEqualTo(422);

        Response first = send("POST", credits, Map.of("amountCents", 2500, "reasonCode", "SERVICE_ISSUE",
                "note", "Outage on Oct 3"), "adj-" + owner);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.text("/status")).isEqualTo("APPLIED");
        assertThat(first.text("/summary")).isEqualTo("$25 credit");
        Response replay = send("POST", credits, Map.of("amountCents", 2500, "reasonCode", "SERVICE_ISSUE",
                "note", "Outage on Oct 3"), "adj-" + owner);
        assertThat(replay.text("/id")).as("same request key, same grant").isEqualTo(first.text("/id"));
        assertThat(column(org, "credit_balance_cents")).as("credited once").isEqualTo("2500");

        // Trial override while trialing: the trial ends N days after it started and the cycle restarts there.
        Response trial = post("/v1/orgs/" + org + "/billing/trial", Map.of("days", 45, "reasonCode",
                "ONBOARDING_DELAY"));
        assertThat(trial.status()).isEqualTo(201);
        assertThat(trial.text("/type")).isEqualTo("TRIAL_END");
        assertThat(column(org, "trial_days_override")).isEqualTo("45");
        assertThat(column(org, "trial_override_reason")).isEqualTo("ONBOARDING_DELAY");
        Instant trialEnd = Instant.parse(column(org, "trial_ends_at").replace(' ', 'T').replaceAll("\\+00$", "Z"));
        assertThat(trialEnd).isAfter(Instant.now().plus(Duration.ofDays(44)));
        assertThat(column(org, "anchor_date")).isEqualTo(column(org, "trial_ends_at"));

        Response days = post("/v1/orgs/" + org + "/billing/free-days", Map.of("days", 10, "reasonCode",
                "RETENTION"));
        assertThat(days.text("/summary")).isEqualTo("10 days free");
        Instant afterDays = Instant.parse(column(org, "trial_ends_at").replace(' ', 'T').replaceAll("\\+00$", "Z"));
        assertThat(Duration.between(trialEnd, afterDays)).isEqualTo(Duration.ofDays(10));

        Response months = post("/v1/orgs/" + org + "/billing/free-months", Map.of("months", 3, "reasonCode",
                "PROMOTION", "note", "Launch partner"));
        assertThat(months.status()).isEqualTo(201);
        assertThat(months.text("/type")).isEqualTo("FREE_MONTHS");
        assertThat(months.text("/summary")).isEqualTo("3 months free");
        Instant afterMonths = Instant.parse(column(org, "trial_ends_at").replace(' ', 'T').replaceAll("\\+00$", "Z"));
        assertThat(afterMonths).as("three calendar months later").isBetween(afterDays.plus(Duration.ofDays(88)),
                afterDays.plus(Duration.ofDays(93)));
        assertThat(post("/v1/orgs/" + org + "/billing/free-months", Map.of("months", 99, "reasonCode",
                "PROMOTION")).status()).isEqualTo(422);

        // An open invoice can be waived with a reason; a settled one can't.
        String declined = declineDefault(owner, org);
        assertThat(declined).isNotBlank();
        TestActors.as(TestActors.staff(unique("finance"), SellerPermissions.BILLING_ADJUST));
        jdbc.sql("update subscription set trial_ends_at = null, status = 'ACTIVE', current_period_end = now() - "
                + "interval '1 hour', anchor_date = now() - interval '1 hour', credit_balance_cents = 0 where org_id "
                + "= :o").param("o", org).update();
        renew();
        String invoice = jdbc.sql("select stripe_invoice_id from invoice where org_id = :o").param("o", org)
                .query(String.class).single();
        assertThat(jdbc.sql("select status from invoice where stripe_invoice_id = :i").param("i", invoice)
                .query(String.class).single()).as("the declined card left it open").isEqualTo("open");
        Response waived = post("/v1/orgs/" + org + "/billing/invoices/" + invoice + "/waive",
                Map.of("reasonCode", "BILLING_ERROR"));
        assertThat(waived.status()).isEqualTo(201);
        assertThat(waived.text("/type")).isEqualTo("WAIVE_INVOICE");
        assertThat(jdbc.sql("select status || ':' || waived_reason_code from invoice where stripe_invoice_id = :i")
                .param("i", invoice).query(String.class).single()).isEqualTo("paid:BILLING_ERROR");
        assertThat(jdbc.sql("select source || ':' || amount_cents from invoice_payment where invoice_id = :i")
                .param("i", invoice).query(String.class).list()).containsExactly("WAIVER:12900");
        assertThat(post("/v1/orgs/" + org + "/billing/invoices/" + invoice + "/waive",
                Map.of("reasonCode", "BILLING_ERROR")).text("/code")).isEqualTo("seller.invoice-not-open");
        assertThat(column(org, "status")).as("good standing again").isEqualTo("ACTIVE");

        Response panel = get("/v1/orgs/" + org + "/billing/panel");
        assertThat(panel.status()).isEqualTo(200);
        assertThat(panel.text("/billing/planCode")).isEqualTo("seller_pro");
        assertThat(panel.body().path("grants").size()).isEqualTo(5);
        assertThat(panel.body().path("invoices").get(0).path("status").asString()).isEqualTo("paid");
        assertThat(panel.body().path("invoices").get(0).path("waivedCents").asLong()).isEqualTo(12900);
        assertThat(panel.body().path("earningsCollectionEnabled").asBoolean()).as("on in the local profile").isTrue();
        assertThat(panel.text("/planFeeCollection/mode")).as("the platform default").isEqualTo("CARD");
        Response log = get("/v1/billing/grants?orgId=" + org + "&type=CREDIT");
        assertThat(log.body().path("total").asLong()).isEqualTo(1);
        assertThat(log.body().path("items").get(0).path("reasonCode").asString()).isEqualTo("SERVICE_ISSUE");
        assertThat(get("/v1/billing/reason-codes").body().valueStream().map(r -> r.path("code").asString()))
                .contains("GOODWILL", "OTHER");

        TestActors.as(TestActors.applicant(owner));
        assertThat(get("/v1/orgs/" + org + "/billing/panel").status()).as("staff only").isEqualTo(403);
        Response earned = get("/v1/orgs/" + org + "/billing/rewards");
        assertThat(earned.body().path("earned").valueStream().map(e -> e.path("type").asString()))
                .contains("CREDIT", "FREE_DAYS", "FREE_MONTHS").doesNotContain("WAIVE_INVOICE");
    }

    /** Makes a card the platform will decline the default method and takes the seller off earnings. */
    private String declineDefault(String owner, String org) {
        TestActors.as(TestActors.applicant(owner));
        String id = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_decline_" + unique("d"), "kind", "CARD", "brand", "visa", "last4", "0002", "expMonth", 8,
                "expYear", 2031, "makeDefault", true)).body().path("id").asString();
        jdbc.sql("update subscription set plan_fee_collection = 'CARD' where org_id = :o").param("o", org).update();
        return id;
    }

    private void renew() {
        renewals.renewDueSubscriptions();
    }

    private Response send(String method, String path, Object body, String idempotencyKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
                            StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), JSON.readTree(r.body()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String column(String org, String column) {
        return jdbc.sql("select " + column + "::text from subscription where org_id = :o").param("o", org)
                .query(String.class).optional().orElse(null);
    }
}
