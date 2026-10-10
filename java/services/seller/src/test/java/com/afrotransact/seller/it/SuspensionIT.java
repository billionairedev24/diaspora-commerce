package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S228: Trust and Safety suspension is its own hold, separate from Stripe compliance payout holds. */
class SuspensionIT extends SellerIntegrationTest {

    private static final String CONNECT = "/v1/webhooks/stripe-connect";

    private static void asTrustOps() {
        TestActors.as(TestActors.service("trust-ops-service", SellerPermissions.SELLERS_ENFORCE));
    }

    @Test
    void suspendHidesListingsPausesPayoutsAndReinstateRestoresThem() {
        String org = approvedSeller(unique("su"), "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        asTrustOps();

        Response suspended = post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "COUNTERFEIT",
                "note", "Counterfeit wax prints"));
        assertThat(suspended.status()).as(suspended.body().toString()).isEqualTo(200);
        assertThat(suspended.text("/status")).isEqualTo("SUSPENDED");
        assertThat(suspended.body().path("suspended").asBoolean()).isTrue();
        assertThat(suspended.body().path("payoutsPaused").asBoolean()).isTrue();
        assertThat(suspended.body().path("listingsHidden").asBoolean()).isTrue();
        assertThat(suspended.body().path("compliancePayoutHold").asBoolean()).isFalse();
        assertThat(suspended.text("/suspension/reasonCode")).isEqualTo("COUNTERFEIT");
        assertThat(suspended.text("/suspension/actorId")).isEqualTo("trust-ops-service");

        JsonNode hidden = lastEvent(SellerEvents.LISTINGS_HIDDEN, org);
        assertThat(hidden.path("reason").asString()).isEqualTo("SUSPENDED");
        assertThat(hidden.path("accountId").asString()).isEqualTo(acct);
        assertThat(hidden.path("note").asString()).isEqualTo("[COUNTERFEIT] Counterfeit wax prints");
        assertThat(lastEvent(SellerEvents.PAYOUTS_PAUSED, org).path("reason").asString()).isEqualTo("SUSPENDED");
        assertThat(lastEvent(SellerEvents.ORG_UPDATED, org).path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(column("select status from organisation where id = :o", org)).isEqualTo("SUSPENDED");

        // Idempotent: a retried suspension publishes nothing new.
        assertThat(post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "COUNTERFEIT")).status())
                .isEqualTo(200);
        assertThat(events(SellerEvents.LISTINGS_HIDDEN, org)).isEqualTo(1);
        assertThat(events(SellerEvents.PAYOUTS_PAUSED, org)).isEqualTo(1);

        Response reinstated = post("/v1/orgs/" + org + "/reinstate", Map.of("note", "Supplier invoices verified"));
        assertThat(reinstated.status()).isEqualTo(200);
        assertThat(reinstated.text("/status")).isEqualTo("APPROVED");
        assertThat(reinstated.body().path("suspended").asBoolean()).isFalse();
        assertThat(reinstated.body().path("payoutsPaused").asBoolean()).isFalse();
        assertThat(reinstated.body().path("history").get(0).path("reinstatedBy").asString())
                .isEqualTo("trust-ops-service");
        assertThat(lastEvent(SellerEvents.PAYOUTS_RESUMED, org).path("reason").asString()).isEqualTo("REINSTATED");
        assertThat(lastEvent(SellerEvents.LISTINGS_RESTORED, org).path("reason").asString()).isEqualTo("REINSTATED");
        assertThat(lastEvent(SellerEvents.ORG_UPDATED, org).path("status").asString()).isEqualTo("APPROVED");
        assertThat(post("/v1/orgs/" + org + "/reinstate", Map.of()).status()).isEqualTo(200);
        assertThat(events(SellerEvents.PAYOUTS_RESUMED, org)).isEqualTo(1);

        assertThat(jdbc.sql("select event_type from connect_account_history where account_id = :a and source = "
                + "'TRUST_SAFETY' order by occurred_at").param("a", acct).query(String.class).list())
                .containsExactly("org.suspended", "org.reinstated");
    }

    @Test
    void reinstatingKeepsAnUnrelatedCompliancePayoutHold() {
        String org = approvedSeller(unique("sh"), "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        TestActors.as(TestActors.staff(unique("ops"), SellerPermissions.COMPLIANCE_REMIND));
        post("/v1/compliance/accounts/" + acct + "/payouts/pause", Map.of("note", "Fraud check"));
        assertThat(events(SellerEvents.PAYOUTS_PAUSED, org)).isEqualTo(1);

        asTrustOps();
        post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "FRAUD_RISK", "note", "Chargeback ring"));
        Response reinstated = post("/v1/orgs/" + org + "/reinstate", Map.of("note", "Cleared by T&S"));

        assertThat(reinstated.body().path("suspended").asBoolean()).isFalse();
        assertThat(reinstated.body().path("payoutsPaused").asBoolean()).isTrue();
        assertThat(reinstated.body().path("compliancePayoutHold").asBoolean()).isTrue();
        assertThat(reinstated.text("/complianceHoldReason")).isEqualTo("MANUAL");
        assertThat(events(SellerEvents.PAYOUTS_RESUMED, org)).as("the compliance hold stays").isZero();
        assertThat(events(SellerEvents.LISTINGS_RESTORED, org)).isEqualTo(1);
        assertThat(column("select payouts_paused::text from connect_account where account_id = :o", acct))
                .isEqualTo("true");
    }

    @Test
    void complianceReleasesWaitUntilTheSuspensionIsLifted() {
        String org = approvedSeller(unique("sw"), "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        long t0 = System.currentTimeMillis() / 1000 + 5;
        String chargesOff = StripeEvents.event(unique("evt"), "account.updated", t0, acct,
                StripeEvents.account(acct, List.of(), List.of(), List.of(), null, "rejected.other", false));
        webhook(CONNECT, chargesOff, sign(chargesOff, CONNECT_SECRET));
        TestActors.as(TestActors.staff(unique("ops"), SellerPermissions.COMPLIANCE_REMIND));
        post("/v1/compliance/accounts/" + acct + "/payouts/pause", Map.of("note", "Review"));

        asTrustOps();
        post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "POLICY_VIOLATION", "note", "Repeat offender"));

        // Compliance clears while the seller is suspended: nothing is released yet.
        String chargesOn = StripeEvents.event(unique("evt"), "account.updated", t0 + 10, acct,
                StripeEvents.account(acct, List.of(), List.of(), List.of(), null, null, true));
        webhook(CONNECT, chargesOn, sign(chargesOn, CONNECT_SECRET));
        TestActors.as(TestActors.staff(unique("tns"), SellerPermissions.COMPLIANCE_HOLD));
        assertThat(post("/v1/compliance/accounts/" + acct + "/payouts/resume", Map.of()).body()
                .path("payoutsPaused").asBoolean()).isFalse();
        assertThat(events(SellerEvents.LISTINGS_RESTORED, org)).isZero();
        assertThat(events(SellerEvents.PAYOUTS_RESUMED, org)).isZero();

        asTrustOps();
        post("/v1/orgs/" + org + "/reinstate", Map.of("note", "Done"));
        assertThat(events(SellerEvents.LISTINGS_RESTORED, org)).isEqualTo(1);
        assertThat(events(SellerEvents.PAYOUTS_RESUMED, org)).isEqualTo(1);
    }

    @Test
    void rulesPermissionsAndServiceAccessToAccountHealth() {
        String org = approvedSeller(unique("sr"), "seller_essentials", Cadence.MONTHLY);

        TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.COMPLIANCE_REMIND));
        assertThat(post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "OTHER", "note", "x")).status())
                .isEqualTo(403);
        assertThat(get("/v1/orgs/" + org + "/enforcement").status()).isEqualTo(200);
        TestActors.as(TestActors.member(unique("owner"), org, "seller.owner"));
        assertThat(post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "OTHER", "note", "x")).status())
                .isEqualTo(403);

        asTrustOps();
        Response noNote = post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "OTHER"));
        assertThat(noNote.status()).isEqualTo(422);
        assertThat(noNote.text("/fieldErrors/note")).isNotNull();
        assertThat(post("/v1/orgs/" + org + "/suspend", Map.of("note", "x")).status()).isEqualTo(400);
        assertThat(post("/v1/orgs/org_missing/suspend", Map.of("reasonCode", "FRAUD_RISK")).status())
                .isEqualTo(404);

        // Service tokens with sellers:enforce (or compliance:hold) read any organisation's Account health.
        Response health = get("/v1/orgs/" + org + "/account-health");
        assertThat(health.status()).as(health.body().toString()).isEqualTo(200);
        assertThat(health.text("/orgId")).isEqualTo(org);
        TestActors.as(TestActors.service("offers-service", "offers:write"));
        assertThat(get("/v1/orgs/" + org + "/account-health").status()).isEqualTo(403);
    }

    @Test
    void onlyApprovedOrganisationsCanBeSuspended() {
        String user = unique("draft");
        TestActors.as(TestActors.applicant(user));
        String org = onboarding.start(OrgKind.SELLER).application().application().orgId();
        asTrustOps();
        Response refused = post("/v1/orgs/" + org + "/suspend", Map.of("reasonCode", "FRAUD_RISK"));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.text("/code")).isEqualTo("seller.org-not-approved");
    }

    private String column(String sql, String id) {
        return jdbc.sql(sql).param("o", id).query(String.class).single();
    }
}
