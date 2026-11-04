package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.ComplianceService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S099–S102: signed, de-duplicated, ordered Connect webhooks, enforcement, reminders, Account health, console. */
class ComplianceWebhookIT extends SellerIntegrationTest {

    private static final String CONNECT = "/v1/webhooks/stripe-connect";

    private ComplianceService compliance;

    @BeforeEach
    void wire(@Autowired ComplianceService complianceService) {
        this.compliance = complianceService;
    }

    @Test
    void pastDuePausesPayoutsDuplicatesAreIgnoredAndClearingResumes() {
        String org = approvedSeller(unique("pd"), "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        long t0 = System.currentTimeMillis() / 1000 + 5;

        String pastDue = StripeEvents.event(unique("evt"), "account.updated", t0, acct, StripeEvents.account(acct,
                List.of(), List.of("individual.id_number"), List.of(), null, "requirements.past_due", true));
        Response first = webhook(CONNECT, pastDue, sign(pastDue, CONNECT_SECRET));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.text("/outcome")).isEqualTo("applied");
        assertThat(events(SellerEvents.PAYOUTS_PAUSED, org)).isEqualTo(1);
        long changed = events(SellerEvents.COMPLIANCE_CHANGED, org);
        assertThat(changed).isGreaterThanOrEqualTo(1);
        JsonNode paused = lastEvent(SellerEvents.PAYOUTS_PAUSED, org);
        assertThat(paused.path("reason").asString()).isEqualTo("AUTO_PAST_DUE");
        assertThat(paused.path("accountId").asString()).isEqualTo(acct);
        assertThat(column(acct, "status")).isEqualTo("RESTRICTED");
        assertThat(column(acct, "payouts_paused")).isEqualTo("true");
        JsonNode change = lastEvent(SellerEvents.COMPLIANCE_CHANGED, org);
        assertThat(change.path("newItems").get(0).asString()).isEqualTo("individual.id_number");
        assertThat(change.path("channels").valueStream().map(JsonNode::asString)).containsExactly("email", "sms",
                "in_app");

        Response duplicate = webhook(CONNECT, pastDue, sign(pastDue, CONNECT_SECRET));
        assertThat(duplicate.body().path("duplicate").asBoolean()).isTrue();
        assertThat(events(SellerEvents.PAYOUTS_PAUSED, org)).isEqualTo(1);
        assertThat(events(SellerEvents.COMPLIANCE_CHANGED, org)).isEqualTo(changed);

        String stale = StripeEvents.event(unique("evt"), "account.updated", t0 - 600, acct, StripeEvents.account(acct,
                List.of(), List.of(), List.of(), null, null, true));
        assertThat(webhook(CONNECT, stale, sign(stale, CONNECT_SECRET)).text("/outcome")).isEqualTo("stale");
        assertThat(column(acct, "payouts_paused")).isEqualTo("true");

        String cleared = StripeEvents.event(unique("evt"), "account.updated", t0 + 10, acct,
                StripeEvents.account(acct, List.of(), List.of(), List.of(), null, null, true));
        assertThat(webhook(CONNECT, cleared, sign(cleared, CONNECT_SECRET)).text("/outcome")).isEqualTo("applied");
        assertThat(events(SellerEvents.PAYOUTS_RESUMED, org)).isEqualTo(1);
        assertThat(lastEvent(SellerEvents.PAYOUTS_RESUMED, org).path("reason").asString()).isEqualTo("CLEARED");
        assertThat(column(acct, "payouts_paused")).isEqualTo("false");
        assertThat(column(acct, "status")).isEqualTo("COMPLIANT");

        String chargesOff = StripeEvents.event(unique("evt"), "account.updated", t0 + 20, acct,
                StripeEvents.account(acct, List.of(), List.of(), List.of(), null, "rejected.other", false));
        webhook(CONNECT, chargesOff, sign(chargesOff, CONNECT_SECRET));
        assertThat(events(SellerEvents.LISTINGS_HIDDEN, org)).isEqualTo(1);
        String chargesOn = StripeEvents.event(unique("evt"), "account.updated", t0 + 30, acct,
                StripeEvents.account(acct, List.of(), List.of(), List.of(), null, null, true));
        webhook(CONNECT, chargesOn, sign(chargesOn, CONNECT_SECRET));
        assertThat(events(SellerEvents.LISTINGS_RESTORED, org)).isEqualTo(1);

        Response forged = webhook(CONNECT, cleared.replace("\"applied\"", ""), sign(cleared, "whsec_wrong"));
        assertThat(forged.status()).isEqualTo(400);
        assertThat(forged.text("/code")).isEqualTo("seller.invalid-signature");
        assertThat(webhook(CONNECT, cleared, null).status()).isEqualTo(400);

        assertThat(jdbc.sql("select count(*) from connect_account_history where account_id = :a and source = "
                + "'STRIPE_WEBHOOK'").param("a", acct).query(Long.class).single()).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.sql("select outcome from stripe_webhook_event where event_id = :e")
                .param("e", JSON.readTree(stale).path("id").asString()).query(String.class).single())
                .isEqualTo("stale");
    }

    @Test
    void otherConnectEventsAreMirroredAndDeauthorizationRestricts() {
        String org = approvedSeller(unique("ev"), "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        long t = System.currentTimeMillis() / 1000 + 5;
        String cap = StripeEvents.event(unique("evt"), "capability.updated", t, acct,
                "{\"id\":\"transfers\",\"object\":\"capability\",\"account\":\"" + acct + "\",\"status\":\"pending\"}");
        assertThat(webhook(CONNECT, cap, sign(cap, CONNECT_SECRET)).text("/outcome")).isEqualTo("applied");
        assertThat(jdbc.sql("select capabilities->>'transfers' from connect_account where account_id = :a")
                .param("a", acct).query(String.class).single()).isEqualTo("pending");
        String person = StripeEvents.event(unique("evt"), "person.updated", t, acct, "{\"id\":\"person_Rep1\","
                + "\"object\":\"person\",\"account\":\"" + acct + "\",\"verification\":{\"status\":\"verified\"},"
                + "\"relationship\":{\"representative\":true}}");
        webhook(CONNECT, person, sign(person, CONNECT_SECRET));
        assertThat(jdbc.sql("select persons->>'person_Rep1' from connect_account where account_id = :a")
                .param("a", acct).query(String.class).single()).isEqualTo("verified");
        long before = events(SellerEvents.COMPLIANCE_CHANGED, org);
        String failed = StripeEvents.event(unique("evt"), "payout.failed", t, acct, "{\"id\":\"po_1\",\"object\":"
                + "\"payout\",\"amount\":12500,\"currency\":\"usd\",\"failure_code\":\"account_closed\"}");
        webhook(CONNECT, failed, sign(failed, CONNECT_SECRET));
        assertThat(events(SellerEvents.COMPLIANCE_CHANGED, org)).isEqualTo(before + 1);
        assertThat(lastEvent(SellerEvents.COMPLIANCE_CHANGED, org).path("trigger").asString())
                .isEqualTo("payout.failed");
        String bank = StripeEvents.event(unique("evt"), "account.external_account.updated", t, acct,
                "{\"id\":\"ba_1\",\"object\":\"bank_account\",\"bank_name\":\"Chase\",\"last4\":\"6789\","
                        + "\"status\":\"errored\"}");
        webhook(CONNECT, bank, sign(bank, CONNECT_SECRET));
        assertThat(column(acct, "external_account_status")).isEqualTo("errored");
        assertThat(lastEvent(SellerEvents.COMPLIANCE_CHANGED, org).path("trigger").asString())
                .isEqualTo("external_account.errored");
        // Account health shows the payout account Stripe holds (bank name, last 4, status; never the numbers).
        TestActors.as(TestActors.member(unique("fin"), org, "seller.finance"));
        Response health = get("/v1/orgs/" + org + "/account-health");
        assertThat(health.text("/payoutBank/last4")).isEqualTo("6789");
        assertThat(health.text("/payoutBank/status")).isEqualTo("errored");
        TestActors.reset();
        String replaced = StripeEvents.event(unique("evt"), "account.external_account.created", t, acct,
                "{\"id\":\"ba_2\",\"object\":\"bank_account\",\"account\":\"" + acct + "\","
                        + "\"bank_name\":\"Wells Fargo\",\"last4\":\"4321\",\"status\":\"new\"}");
        assertThat(webhook(CONNECT, replaced, sign(replaced, CONNECT_SECRET)).text("/outcome")).isEqualTo("applied");
        assertThat(column(acct, "external_account_status")).isEqualTo("new");
        String card = StripeEvents.event(unique("evt"), "account.external_account.created", t, acct,
                "{\"id\":\"card_1\",\"object\":\"card\",\"account\":\"" + acct + "\",\"last4\":\"4242\"}");
        assertThat(webhook(CONNECT, card, sign(card, CONNECT_SECRET)).text("/outcome"))
                .isEqualTo("ignored-not-a-bank-account");
        String unknown = StripeEvents.event(unique("evt"), "account.updated", t, "acct_unknown",
                StripeEvents.account("acct_unknown", List.of(), List.of(), List.of(), null, null, true));
        assertThat(webhook(CONNECT, unknown, sign(unknown, CONNECT_SECRET)).text("/outcome"))
                .isEqualTo("ignored-unknown-account");

        String deauth = StripeEvents.event(unique("evt"), "account.application.deauthorized", t, acct,
                "{\"id\":\"ca_platform\",\"object\":\"application\"}");
        webhook(CONNECT, deauth, sign(deauth, CONNECT_SECRET));
        assertThat(column(acct, "status")).isEqualTo("RESTRICTED");
        assertThat(events(SellerEvents.PAYOUTS_PAUSED, org)).isEqualTo(1);
        assertThat(events(SellerEvents.LISTINGS_HIDDEN, org)).isEqualTo(1);
        assertThat(lastEvent(SellerEvents.PAYOUTS_PAUSED, org).path("reason").asString()).isEqualTo("DEAUTHORIZED");
    }

    @Test
    void accountHealthIsReadOnlyAndStripesComponentsCollectEveryAnswer() {
        String owner = unique("ah");
        String org = approvedSeller(owner, "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        long now = System.currentTimeMillis() / 1000;
        String due = StripeEvents.event(unique("evt"), "account.updated", now + 5, acct, StripeEvents.account(acct,
                List.of("business_profile.url", "company.verification.document", "company.tax_id", "tos_acceptance",
                        "representative.dob.day"), List.of(), List.of(), now + 2 * 86400 + 3600, null, true));
        webhook(CONNECT, due, sign(due, CONNECT_SECRET));
        assertThat(column(acct, "status")).isEqualTo("RESTRICTED_SOON");

        // Reminders 7/3/1 days out: the 3-day one goes out once, however often the job runs.
        compliance.sendDueReminders();
        compliance.sendDueReminders();
        assertThat(events(SellerEvents.COMPLIANCE_REMINDER, org)).isEqualTo(1);
        JsonNode reminder = lastEvent(SellerEvents.COMPLIANCE_REMINDER, org);
        assertThat(reminder.path("thresholdDays").asInt()).isEqualTo(3);
        assertThat(reminder.path("manual").asBoolean()).isFalse();
        assertThat(column(acct, "reminders_sent")).isEqualTo("1");

        TestActors.as(TestActors.member(unique("cat"), org, "seller.catalog"));
        assertThat(get("/v1/orgs/" + org + "/account-health").status()).isEqualTo(403);
        TestActors.as(TestActors.member(unique("fin"), org, "seller.finance"));
        assertThat(get("/v1/orgs/" + org + "/account-health").status()).isEqualTo(200);
        TestActors.as(TestActors.applicant(owner));
        Response health = get("/v1/orgs/" + org + "/account-health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.text("/status")).isEqualTo("RESTRICTED_SOON");
        assertThat(health.body().path("requirements").size()).isEqualTo(5);
        JsonNode url = health.body().path("requirements").valueStream()
                .filter(r -> r.path("requirement").asString().equals("business_profile.url")).findFirst().orElseThrow();
        assertThat(url.at("/form/kind").asString()).isEqualTo("BUSINESS_URL");
        assertThat(url.path("bucket").asString()).isEqualTo("CURRENTLY_DUE");

        // Stripe-collected account: nothing is answered through AfroTransact. Each item names the embedded component
        // that collects it, and owner / finance members open an Account Session for those components.
        assertThat(url.at("/form/component").asString()).isEqualTo("account_onboarding");
        assertThat(post("/v1/orgs/" + org + "/account-health/requirements/business_profile.url",
                Map.of("values", Map.of("url", "https://store.example.com"))).status()).isIn(404, 405);
        Response session = post("/v1/orgs/" + org + "/stripe/account-session", Map.of());
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.text("/accountId")).isEqualTo(acct);
        assertThat(session.text("/clientSecret")).isEqualTo("accs_secret_test");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/account_sessions")).anySatisfy(b -> assertThat(b)
                .contains("account=" + acct, "components[account_management][enabled]=true",
                        "components[notification_banner][enabled]=true").doesNotContain("external_account_collection"));
        TestActors.as(TestActors.member(unique("cat"), org, "seller.catalog"));
        assertThat(post("/v1/orgs/" + org + "/stripe/account-session", Map.of()).status()).isEqualTo(403);
        TestActors.as(TestActors.staff(unique("ops"), SellerPermissions.COMPLIANCE_HOLD));
        assertThat(post("/v1/orgs/" + org + "/stripe/account-session", Map.of()).status())
                .as("staff can't open the seller's own Stripe account").isEqualTo(403);
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/accounts/" + acct)).isEmpty();
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/files")).isEmpty();
    }

    @Test
    void staffConsoleRemindsPausesAndReleasesByRole() {
        String org = approvedSeller(unique("sc"), "seller_essentials", Cadence.MONTHLY);
        String acct = accountOf(org);
        long now = System.currentTimeMillis() / 1000;
        String due = StripeEvents.event(unique("evt"), "account.updated", now + 5, acct, StripeEvents.account(acct,
                List.of("tos_acceptance"), List.of(), List.of(), now + 12 * 86400, null, true));
        webhook(CONNECT, due, sign(due, CONNECT_SECRET));

        TestActors.as(TestActors.staff(unique("viewer")));
        assertThat(get("/v1/compliance/accounts?openOnly=true").status()).isEqualTo(403);
        TestActors.as(TestActors.staff(unique("ops"), SellerPermissions.COMPLIANCE_REMIND));
        Response list = get("/v1/compliance/accounts?openOnly=true&status=ACTION_NEEDED");
        assertThat(list.body().valueStream().map(a -> a.path("accountId").asString())).contains(acct);
        Response reminded = post("/v1/compliance/accounts/" + acct + "/reminders", Map.of());
        assertThat(reminded.body().path("remindersSent").asInt()).isEqualTo(1);
        assertThat(lastEvent(SellerEvents.COMPLIANCE_REMINDER, org).path("manual").asBoolean()).isTrue();
        Response paused = post("/v1/compliance/accounts/" + acct + "/payouts/pause", Map.of("note", "Fraud check"));
        assertThat(paused.body().path("payoutsPaused").asBoolean()).isTrue();
        assertThat(paused.text("/holdReason")).isEqualTo("MANUAL");
        assertThat(lastEvent(SellerEvents.PAYOUTS_PAUSED, org).path("note").asString()).isEqualTo("Fraud check");
        assertThat(post("/v1/compliance/accounts/" + acct + "/payouts/resume", Map.of()).status()).isEqualTo(403);
        TestActors.as(TestActors.staff(unique("tns"), SellerPermissions.COMPLIANCE_HOLD));
        Response resumed = post("/v1/compliance/accounts/" + acct + "/payouts/resume", Map.of("note", "Cleared"));
        assertThat(resumed.body().path("payoutsPaused").asBoolean()).isFalse();
        assertThat(events(SellerEvents.PAYOUTS_RESUMED, org)).isEqualTo(1);
        Response detail = get("/v1/compliance/accounts/" + acct);
        assertThat(detail.body().path("history").valueStream().map(h -> h.path("eventType").asString()))
                .contains("payouts.paused", "payouts.resumed", "reminder.sent", "account.updated");
    }

    private String column(String acct, String column) {
        return jdbc.sql("select " + column + "::text from connect_account where account_id = :a").param("a", acct)
                .query(String.class).single();
    }
}
