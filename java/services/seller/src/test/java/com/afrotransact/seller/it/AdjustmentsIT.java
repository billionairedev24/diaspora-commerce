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

import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S295 and S297: trial campaigns (promo codes, sign-up windows), the per-seller trial override, and staff adjustments
 * (credit, free days, waived invoice) with reason codes and request idempotency; the per-seller billing panel.
 */
class AdjustmentsIT extends SellerIntegrationTest {

    private static final String BILLING_HOOK = "/v1/webhooks/stripe-billing";

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
        String customer = column(org, "stripe_customer_id");
        String body = StripeStub.bodies(STRIPE, "POST", "/v1/subscriptions").stream()
                .filter(b -> b.contains("customer=" + customer)).findFirst().orElseThrow();
        long trialEnd = Long.parseLong(body.replaceAll(".*trial_end=(\\d+).*", "$1"));
        assertThat(trialEnd).as("60 days, not the plan's 30").isBetween(before.plus(Duration.ofDays(60))
                .getEpochSecond() - 5, Instant.now().plus(Duration.ofDays(60)).getEpochSecond() + 5);
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
    void staffAdjustmentsAreAuditedIdempotentAndAppliedAtStripe() {
        String owner = unique("adj");
        String org = approvedSeller(owner, "seller_pro", Cadence.MONTHLY);
        String customer = column(org, "stripe_customer_id");
        String sub = column(org, "stripe_subscription_id");
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
        List<LoggedRequest> balance = StripeStub.requests(STRIPE, "POST", "/v1/customers/" + customer
                + "/balance_transactions");
        assertThat(balance).hasSize(1);
        assertThat(balance.getFirst().getHeader("Idempotency-Key")).isEqualTo("grant-" + first.text("/id")
                + "-balance");
        assertThat(StripeStub.decoded(balance.getFirst())).contains("amount=-2500", "currency=usd",
                "description=$25 credit");

        // Trial override while trialing: the trial ends N days after it started.
        Response trial = post("/v1/orgs/" + org + "/billing/trial", Map.of("days", 45, "reasonCode",
                "ONBOARDING_DELAY"));
        assertThat(trial.status()).isEqualTo(201);
        assertThat(trial.text("/type")).isEqualTo("TRIAL_END");
        assertThat(column(org, "trial_days_override")).isEqualTo("45");
        assertThat(column(org, "trial_override_reason")).isEqualTo("ONBOARDING_DELAY");
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub)).anySatisfy(r ->
                assertThat(r.getHeader("Idempotency-Key")).isEqualTo("grant-" + trial.text("/id") + "-trial"));

        Response days = post("/v1/orgs/" + org + "/billing/free-days", Map.of("days", 10, "reasonCode",
                "RETENTION"));
        assertThat(days.text("/summary")).isEqualTo("10 days free");
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub)).anySatisfy(r -> {
            assertThat(r.getHeader("Idempotency-Key")).isEqualTo("grant-" + days.text("/id") + "-trial");
            assertThat(StripeStub.decoded(r)).contains("trial_end=", "proration_behavior=none");
        });

        // A failed invoice can be waived (voided at Stripe); a paid one can't.
        String invoice = "in_" + unique("w").replace("-", "");
        long next = System.currentTimeMillis() / 1000 + 86400;
        String failed = StripeEvents.event(unique("evt"), "invoice.payment_failed", next, null,
                StripeEvents.invoice(invoice, customer, "open", 12900, 1, next));
        webhook(BILLING_HOOK, failed, sign(failed, BILLING_SECRET));
        TestActors.as(TestActors.staff(unique("finance"), SellerPermissions.BILLING_ADJUST));
        Response waived = post("/v1/orgs/" + org + "/billing/invoices/" + invoice + "/waive",
                Map.of("reasonCode", "BILLING_ERROR"));
        assertThat(waived.status()).isEqualTo(201);
        assertThat(waived.text("/type")).isEqualTo("WAIVE_INVOICE");
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/invoices/" + invoice + "/void").getFirst()
                .getHeader("Idempotency-Key")).isEqualTo("grant-" + waived.text("/id") + "-void");
        assertThat(jdbc.sql("select status from invoice where stripe_invoice_id = :i").param("i", invoice)
                .query(String.class).single()).isEqualTo("void");
        assertThat(post("/v1/orgs/" + org + "/billing/invoices/" + invoice + "/waive",
                Map.of("reasonCode", "BILLING_ERROR")).text("/code")).isEqualTo("seller.invoice-not-open");

        // Collection from earnings is switched off on this platform.
        assertThat(put("/v1/orgs/" + org + "/billing/earnings-collection", Map.of("enabled", true)).status())
                .isEqualTo(409);

        Response panel = get("/v1/orgs/" + org + "/billing/panel");
        assertThat(panel.status()).isEqualTo(200);
        assertThat(panel.text("/billing/planCode")).isEqualTo("seller_pro");
        assertThat(panel.body().path("billing").path("creditBalanceCents").asLong()).isEqualTo(2500);
        assertThat(panel.body().path("grants").size()).isEqualTo(4);
        assertThat(panel.body().path("invoices").get(0).path("status").asString()).isEqualTo("void");
        assertThat(panel.body().path("earningsCollectionEnabled").asBoolean()).isFalse();
        Response log = get("/v1/billing/grants?orgId=" + org + "&type=CREDIT");
        assertThat(log.body().path("total").asLong()).isEqualTo(1);
        assertThat(log.body().path("items").get(0).path("reasonCode").asString()).isEqualTo("SERVICE_ISSUE");
        assertThat(get("/v1/billing/reason-codes").body().valueStream().map(r -> r.path("code").asString()))
                .contains("GOODWILL", "OTHER");

        TestActors.as(TestActors.applicant(owner));
        assertThat(get("/v1/orgs/" + org + "/billing/panel").status()).as("staff only").isEqualTo(403);
        Response earned = get("/v1/orgs/" + org + "/billing/rewards");
        assertThat(earned.body().path("earned").valueStream().map(e -> e.path("type").asString()))
                .contains("CREDIT", "FREE_DAYS").doesNotContain("WAIVE_INVOICE");
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
