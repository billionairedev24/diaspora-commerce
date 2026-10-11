package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.port.CallerProfile;
import com.afrotransact.seller.port.FieldCipher;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S089–S098 end to end over HTTP: prefilled draft, autosave and resume, entity-aware validation, Stripe Connect
 * account + Account Session (asserted on WireMock), payouts, plan, permits, submit, two-step review with flag /
 * send back / request info / resubmit, same-person approval refused, final approval by a second person, org approved,
 * outbox events, subscription activation, SSN encryption and PII masking with access logging.
 */
class OnboardingFlowIT extends SellerIntegrationTest {

    private FieldCipher cipher;

    @BeforeEach
    void wire(@Autowired FieldCipher fieldCipher) {
        this.cipher = fieldCipher;
    }

    @Test
    void draftResumeSubmitTwoStepReviewAndApproval() {
        String user = unique("ada");
        TestActors.as(TestActors.applicant(user));
        TestActors.profile(new CallerProfile("Adaeze", "Nwosu", "Adaeze Nwosu", "Adaeze@Example.com",
                "+1 713 249 0142"));

        // Existing customer starts selling: prefilled from identity claims, resumable.
        Response started = post("/v1/applications/me", Map.of("kind", "SELLER"));
        assertThat(started.status()).isEqualTo(201);
        String appId = started.text("/id");
        String orgId = started.text("/orgId");
        assertThat(orgId).startsWith("org_");
        assertThat(started.text("/reference")).startsWith("APP-");
        assertThat(started.text("/status")).isEqualTo("DRAFT");
        assertThat(started.text("/business/phone")).isEqualTo("+17132490142");
        assertThat(started.text("/business/contactEmail")).isEqualTo("adaeze@example.com");
        assertThat(started.text("/payouts/accountHolderName")).isEqualTo("Adaeze Nwosu");
        assertThat(strings(started.body().path("prefilledFields"))).containsExactlyInAnyOrder("business.contactEmail",
                "business.phone", "businessDetails.legalFirstName", "businessDetails.legalLastName",
                "payouts.accountHolderName");
        // A prefilled account holder name is not a payout account: Payouts stays incomplete until Stripe links one.
        assertThat(strings(started.body().path("completedSteps"))).doesNotContain("payouts", "stripe-verification");
        Response resumed = post("/v1/applications/me", Map.of("kind", "SELLER"));
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.text("/id")).isEqualTo(appId);

        // Non-live market: dedicated problem code, then the launch list.
        Response canada = put("/v1/applications/me/steps/business", Map.of("country", "CA"));
        assertThat(canada.status()).isEqualTo(422);
        assertThat(canada.text("/code")).isEqualTo("seller.country-not-live");
        Response launch = post("/v1/launch-list", Map.of("country", "CA", "email", user + "@example.com",
                "shoppingUpdates", true));
        assertThat(launch.status()).isEqualTo(201);
        assertThat(post("/v1/launch-list", Map.of("country", "CA", "email", user + "@example.com",
                "shoppingUpdates", false)).status()).isEqualTo(200);

        Response business = put("/v1/applications/me/steps/business", Map.of("offering", "PRODUCTS",
                "entityType", "sole_proprietor", "country", "US", "storeName", "Ada's Pantry",
                "contactEmail", "adaeze@example.com", "phone", "+17132490142"));
        assertThat(business.status()).isEqualTo(200);
        assertThat(strings(business.body().path("prefilledFields"))).contains("business.phone",
                "business.contactEmail");
        assertThat(strings(business.body().path("steps"))).doesNotContain("owners");

        Response badSsn = put("/v1/applications/me/steps/business-details", Map.of("taxId", "000-00-0000"));
        assertThat(badSsn.status()).isEqualTo(422);
        assertThat(badSsn.body().path("fieldErrors").has("businessDetails.taxId")).isTrue();

        Response details = put("/v1/applications/me/steps/business-details", Map.of("legalFirstName", "Adaeze",
                "legalLastName", "Nwosu", "taxId", "123456789", "dateOfBirth", "1990-04-02",
                "address", Map.of("line1", "9800 Westheimer Rd", "city", "Houston", "state", "TX",
                        "postalCode", "77042", "country", "US")));
        assertThat(details.status()).isEqualTo(200);
        assertThat(details.text("/businessDetails/taxIdMasked")).isEqualTo("•••-••-6789");
        assertThat(details.text("/businessDetails/taxId")).isNull();
        assertThat(details.text("/businessDetails/dateOfBirth")).isEqualTo("1990-04-02");

        // SSN is stored only as last 4 + AES-GCM ciphertext.
        String stored = jdbc.sql("select data::text from application where id = :id").param("id", appId)
                .query(String.class).single();
        assertThat(stored).doesNotContain("123456789").doesNotContain("123-45-6789");
        String sealed = jdbc.sql("select data->'businessDetails'->>'taxIdCipher' from application where id = :id")
                .param("id", appId).query(String.class).single();
        assertThat(sealed).startsWith("k1:");
        assertThat(cipher.decrypt(sealed)).isEqualTo("123-45-6789");

        Response me = get("/v1/applications/me");
        assertThat(me.text("/currentStep")).isEqualTo("business-details");
        assertThat(strings(me.body().path("completedSteps"))).contains("business", "business-details");
        assertThat(put("/v1/applications/me/steps/owners", Map.of("owners", List.of())).text("/code"))
                .isEqualTo("seller.step-not-applicable");

        // Stripe Connect: platform-controlled account, then an Account Session for the embedded components.
        Response session = post("/v1/applications/me/stripe/account-session", Map.of());
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.text("/clientSecret")).isEqualTo("accs_secret_test");
        String accountId = session.text("/accountId");
        assertThat(accountId).startsWith("acct_");
        LoggedRequest create = StripeStub.requests(STRIPE, "POST", "/v1/accounts").stream()
                .filter(r -> StripeStub.decoded(r).contains("metadata[org_id]=" + orgId)).findFirst().orElseThrow();
        assertThat(StripeStub.decoded(create)).contains("controller[fees][payer]=application",
                "controller[losses][payments]=application", "controller[requirement_collection]=application",
                "controller[stripe_dashboard][type]=none", "settings[payouts][schedule][interval]=manual",
                "capabilities[card_payments][requested]=true", "capabilities[transfers][requested]=true",
                "business_type=individual", "country=US");
        assertThat(create.getHeader("Idempotency-Key")).isEqualTo("connect-account-" + orgId);
        assertThat(create.getHeader("Authorization")).isEqualTo("Bearer sk_test_seller_it");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/account_sessions")).anySatisfy(b -> assertThat(b)
                .contains("account=" + accountId, "components[account_onboarding][enabled]=true",
                        "components[account_management][enabled]=true"));
        assertThat(post("/v1/applications/me/stripe/account-session", Map.of()).text("/accountId"))
                .isEqualTo(accountId);

        Response refreshed = post("/v1/applications/me/stripe/refresh", Map.of());
        assertThat(refreshed.body().at("/stripe/detailsSubmitted").asBoolean()).isTrue();
        assertThat(refreshed.text("/stripe/identityStatus")).isEqualTo("verified");
        assertThat(refreshed.text("/stripe/taxIdStatus")).isEqualTo("verified");

        // Payouts: routing checksum, matching account numbers, bank token forwarded to Stripe.
        Response mismatch = put("/v1/applications/me/steps/payouts", Map.of("routingNumber", "021000021",
                "accountNumber", "000123456789", "accountNumberConfirmation", "000123456780"));
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().path("fieldErrors").has("payouts.accountNumberConfirmation")).isTrue();
        Response payouts = put("/v1/applications/me/steps/payouts", Map.of("accountHolderName", "Adaeze Nwosu",
                "accountType", "checking", "bankName", "Chase", "routingNumber", "021000021",
                "accountNumber", "000123456789", "accountNumberConfirmation", "000123456789",
                "externalAccountToken", "btok_us_verified"));
        assertThat(payouts.status()).isEqualTo(200);
        assertThat(payouts.text("/payouts/accountLast4")).isEqualTo("6789");
        assertThat(payouts.body().at("/payouts/externalAccountLinked").asBoolean()).isTrue();
        assertThat(strings(payouts.body().path("prefilledFields"))).contains("payouts.accountHolderName");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/accounts/" + accountId + "/external_accounts"))
                .anySatisfy(b -> assertThat(b).contains("external_account=btok_us_verified"));
        assertThat(strings(payouts.body().path("completedSteps"))).contains("payouts");

        // Without a client-side token the numbers go to Stripe once (as the payout account) and are never stored.
        Response typed = put("/v1/applications/me/steps/payouts", Map.of("accountHolderName", "Adaeze Nwosu",
                "accountType", "checking", "routingNumber", "021000021", "accountNumber", "000987654321",
                "accountNumberConfirmation", "000987654321"));
        assertThat(typed.status()).isEqualTo(200);
        assertThat(typed.body().at("/payouts/externalAccountLinked").asBoolean()).isTrue();
        assertThat(typed.body().toString()).doesNotContain("000987654321");
        assertThat(jdbc.sql("select data::text from application where id = :id").param("id", appId)
                .query(String.class).single()).doesNotContain("000987654321").doesNotContain("000123456789");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/accounts/" + accountId + "/external_accounts"))
                .anySatisfy(b -> assertThat(b).contains("external_account[routing_number]=021000021",
                        "external_account[account_number]=000987654321",
                        "external_account[account_holder_type]=individual"));

        Response plan = put("/v1/applications/me/steps/plan", Map.of("planCode", "seller_growth",
                "cadence", "quarterly", "paymentMethodKind", "card", "stripePaymentMethodId", "pm_card_visa",
                "cardBrand", "visa", "cardLast4", "4242", "cardExpMonth", 12, "cardExpYear", 2030));
        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.body().at("/plan/firstCharge/amountCents").asLong()).isEqualTo(11115);
        assertThat(plan.text("/plan/firstCharge/sentence")).startsWith("Free for 30 days. First charge of $111.15");

        Response incomplete = post("/v1/applications/me/submit", Map.of());
        assertThat(incomplete.status()).isEqualTo(422);
        assertThat(incomplete.text("/code")).isEqualTo("seller.application-incomplete");
        assertThat(incomplete.body().path("fieldErrors").has("compliance.agreementsAccepted")).isTrue();

        Map<String, Object> compliance = Map.of("categories", List.of("groceries"),
                "foodPermit", Map.of("permitNumber", "FE-1001", "issuingAuthority", "Harris County",
                        "expiresOn", "2027-12-31", "documentAssetId", "asset-permit-1",
                        "permitType", "FOOD_ESTABLISHMENT"),
                "agreementsAccepted", true, "w9Certified", true, "w9SignerName", "Adaeze Nwosu");
        assertThat(put("/v1/applications/me/steps/catalog-compliance", compliance).status()).isEqualTo(200);

        Response submitted = post("/v1/applications/me/submit", Map.of());
        assertThat(submitted.status()).isEqualTo(200);
        assertThat(submitted.text("/status")).isEqualTo("PENDING_REVIEW");
        assertThat(events(SellerEvents.APPLICATION_SUBMITTED, appId)).isEqualTo(1);
        assertThat(put("/v1/applications/me/steps/plan", Map.of("planCode", "seller_pro")).text("/code"))
                .isEqualTo("seller.application-locked");

        // Step 1 reviewer without PII access: masked tax ID and DOB; document views are logged.
        String reviewer = unique("reviewer");
        String approver = unique("approver");
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        Response queue = get("/v1/applications?status=PENDING_REVIEW&size=200");
        assertThat(queue.body().path("items").valueStream().map(n -> n.path("id").asString()))
                .contains(appId);
        Response dossier = get("/v1/applications/" + appId);
        assertThat(dossier.text("/businessDetails/taxId")).isNull();
        assertThat(dossier.text("/businessDetails/taxIdMasked")).isEqualTo("•••-••-6789");
        assertThat(dossier.text("/businessDetails/dateOfBirth")).isEqualTo("1990-••-••");
        assertThat(dossier.body().at("/businessDetails/birthYear").asInt()).isEqualTo(1990);
        Response doc = get("/v1/applications/" + appId + "/documents/asset-permit-1");
        assertThat(doc.status()).isEqualTo(200);
        assertThat(doc.text("/kind")).isEqualTo("FOOD_PERMIT");
        assertThat(doc.text("/url")).endsWith("/blobs/asset-permit-1");
        assertThat(get("/v1/applications/" + appId + "/documents/not-mine").status()).isEqualTo(404);
        assertThat(accessCount(appId, "FOOD_PERMIT")).isEqualTo(1);
        assertThat(accessCount(appId, "PII_VIEW")).isZero();

        Response flagged = post("/v1/applications/" + appId + "/review", Map.of("action", "FLAG",
                "reasonCode", "PERMIT_MISSING", "note", "Permit not in county register",
                "failedChecks", List.of("Food permit")));
        assertThat(flagged.text("/status")).isEqualTo("FLAGGED");
        assertThat(strings(flagged.body().path("failedChecks"))).containsExactly("Food permit");
        assertThat(events(SellerEvents.APPLICATION_FLAGGED, appId)).isEqualTo(1);
        assertThat(post("/v1/applications/" + appId + "/review", Map.of("action", "APPROVE", "reasonCode",
                "NOT_A_CODE")).text("/code")).isEqualTo("seller.unknown-reason-code");

        // Step 2 sends it back; the reviewer asks for information.
        TestActors.as(TestActors.staff(approver, SellerPermissions.APPLICATIONS_APPROVE));
        Response back = post("/v1/applications/" + appId + "/decision", Map.of("action", "SEND_BACK",
                "reasonCode", "OTHER", "note", "Ask for the current permit"));
        assertThat(back.text("/status")).isEqualTo("PENDING_REVIEW");
        assertThat(get("/v1/orgs/" + orgId + "/public").text("/status")).isEqualTo("PENDING_REVIEW");
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        Response info = post("/v1/applications/" + appId + "/review", Map.of("action", "REQUEST_INFO",
                "reasonCode", "DOCUMENT_UNREADABLE", "requestedItems", List.of("Current food permit")));
        assertThat(info.text("/status")).isEqualTo("INFO_REQUESTED");
        assertThat(events(SellerEvents.APPLICATION_INFO_REQUESTED, appId)).isEqualTo(1);
        assertThat(lastEvent(SellerEvents.APPLICATION_INFO_REQUESTED, appId).path("requestedItems").get(0)
                .asString()).isEqualTo("Current food permit");

        // Applicant edits and resubmits.
        TestActors.as(TestActors.applicant(user));
        assertThat(get("/v1/applications/me").text("/status")).isEqualTo("INFO_REQUESTED");
        Map<String, Object> newPermit = Map.of("categories", List.of("groceries"),
                "foodPermit", Map.of("permitNumber", "FE-2002", "issuingAuthority", "Harris County",
                        "expiresOn", "2028-06-30", "documentAssetId", "asset-permit-2",
                        "permitType", "FOOD_ESTABLISHMENT"),
                "agreementsAccepted", true, "w9Certified", true, "w9SignerName", "Adaeze Nwosu");
        assertThat(put("/v1/applications/me/steps/catalog-compliance", newPermit).status()).isEqualTo(200);
        Response resubmitted = post("/v1/applications/me/submit", Map.of());
        assertThat(resubmitted.text("/status")).isEqualTo("PENDING_REVIEW");
        assertThat(resubmitted.body().path("submissionCount").asInt()).isEqualTo(2);
        assertThat(events(SellerEvents.APPLICATION_SUBMITTED, appId)).isEqualTo(2);

        // Reviewer approves; the same person can't give final approval even with the permission.
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW,
                SellerPermissions.APPLICATIONS_APPROVE));
        assertThat(post("/v1/applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED")).text("/status")).isEqualTo("AWAITING_FINAL_APPROVAL");
        Response same = post("/v1/applications/" + appId + "/decision", Map.of("action", "FINAL_APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED"));
        assertThat(same.status()).isEqualTo(403);
        assertThat(same.text("/code")).isEqualTo("seller.same-person-approval");
        TestActors.as(TestActors.staff(unique("no-perm"), SellerPermissions.APPLICATIONS_REVIEW));
        assertThat(post("/v1/applications/" + appId + "/decision", Map.of("action", "FINAL_APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED")).status()).isEqualTo(403);

        // A different approver with PII access gives final approval.
        TestActors.as(TestActors.staff(approver, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_PII));
        Response approved = post("/v1/applications/" + appId + "/decision", Map.of("action", "FINAL_APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED", "note", "Second approver"));
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.text("/status")).isEqualTo("APPROVED");
        assertThat(approved.text("/finalApproverId")).isEqualTo(approver);
        assertThat(approved.text("/businessDetails/taxId")).isEqualTo("123-45-6789");
        Response full = get("/v1/applications/" + appId);
        assertThat(full.text("/businessDetails/dateOfBirth")).isEqualTo("1990-04-02");
        assertThat(accessCount(appId, "PII_VIEW")).as("decision + dossier, both logged").isEqualTo(2);
        assertThat(full.body().path("timeline").valueStream().map(t -> t.path("action").asString()))
                .containsExactly("STARTED", "SUBMITTED", "FLAG", "SEND_BACK", "REQUEST_INFO", "RESUBMITTED",
                        "APPROVE", "FINAL_APPROVE");
        JsonNode last = full.body().path("timeline").get(7);
        assertThat(last.path("actorId").asString()).isEqualTo(approver);
        assertThat(last.path("reasonCode").asString()).isEqualTo("ALL_CHECKS_PASSED");
        assertThat(last.path("note").asString()).isEqualTo("Second approver");

        // Organisation approved; identity unlocks the back office from seller.org.approved.
        TestActors.reset();
        Response publicProfile = get("/v1/orgs/" + orgId + "/public");
        assertThat(publicProfile.text("/status")).isEqualTo("APPROVED");
        assertThat(publicProfile.text("/displayName")).isEqualTo("Ada's Pantry");
        assertThat(events(SellerEvents.APPLICATION_APPROVED, appId)).isEqualTo(1);
        assertThat(events(SellerEvents.ORG_APPROVED, orgId)).isEqualTo(1);
        assertThat(events(SellerEvents.ORG_UPDATED, orgId)).isEqualTo(1);
        JsonNode orgApproved = lastEvent(SellerEvents.ORG_APPROVED, orgId);
        assertThat(orgApproved.path("ownerUserIds").get(0).asString()).isEqualTo(user);
        assertThat(orgApproved.path("applicantUserId").asString()).isEqualTo(user);
        assertThat(orgApproved.path("legalName").asString()).isEqualTo("Adaeze Nwosu");

        // Subscription created at Stripe with the plan's trial; card attached as default.
        assertThat(jdbc.sql("select status from subscription where org_id = :o").param("o", orgId)
                .query(String.class).single()).isEqualTo("TRIALING");
        String customer = jdbc.sql("select stripe_customer_id from subscription where org_id = :o").param("o", orgId)
                .query(String.class).single();
        assertThat(customer).startsWith("cus_");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/customers")).anySatisfy(b -> assertThat(b)
                .contains("metadata[org_id]=" + orgId));
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/subscriptions")).anySatisfy(b -> assertThat(b)
                .contains("customer=" + customer, "trial_period_days=30",
                        "metadata[plan_lookup_key]=seller_growth_quarterly"));
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/payment_methods/pm_card_visa/attach")).isNotEmpty();
        assertThat(events(SellerEvents.SUBSCRIPTION_CHANGED, orgId)).isEqualTo(1);
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, orgId).path("change").asString())
                .isEqualTo("ACTIVATED");
    }

    @Test
    void startPrefillsPhoneStoreNameAndAddressFromTheAccountOnlyOnCreation() {
        String user = unique("bisi");
        TestActors.as(TestActors.applicant(user));
        // The token carries no phone_number (unverified phone): the BFF sends the account's phone instead.
        TestActors.profile(new CallerProfile("Bisi", "Adeyemi", "Bisi Adeyemi", "bisi@example.com", null));
        Map<String, Object> prefill = Map.of("phone", "(713) 249-0188", "storeName", "Bisi's Kitchen",
                "address", Map.of("line1", "4410 Bellaire Blvd", "city", "Houston", "state", "tx",
                        "postalCode", "77025"));

        Response started = post("/v1/applications/me", Map.of("kind", "PROVIDER", "prefill", prefill));
        assertThat(started.status()).isEqualTo(201);
        assertThat(started.text("/business/phone")).isEqualTo("+17132490188");
        assertThat(started.text("/business/storeName")).isEqualTo("Bisi's Kitchen");
        assertThat(started.text("/businessDetails/address/line1")).isEqualTo("4410 Bellaire Blvd");
        assertThat(started.text("/businessDetails/address/state")).isEqualTo("TX");
        assertThat(started.text("/businessDetails/address/country")).isEqualTo("US");
        assertThat(strings(started.body().path("prefilledFields"))).containsExactlyInAnyOrder("business.contactEmail",
                "business.phone", "business.storeName", "businessDetails.legalFirstName",
                "businessDetails.legalLastName", "businessDetails.address", "payouts.accountHolderName");

        // Idempotent: the second click resumes the same application and ignores a different prefill.
        Response again = post("/v1/applications/me", Map.of("kind", "PROVIDER",
                "prefill", Map.of("storeName", "Something else")));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("/id")).isEqualTo(started.text("/id"));
        assertThat(again.text("/business/storeName")).isEqualTo("Bisi's Kitchen");

        // Changing a prefilled answer drops its marker; an incomplete address is not prefilled at all.
        assertThat(put("/v1/applications/me/steps/business", Map.of("offering", "SERVICES",
                "entityType", "sole_proprietor", "country", "US", "storeName", "Bisi Braids",
                "contactEmail", "bisi@example.com", "phone", "+17132490188")).status()).isEqualTo(200);
        assertThat(strings(get("/v1/applications/me").body().path("prefilledFields")))
                .contains("business.phone").doesNotContain("business.storeName");

        // Access tokens without profile claims: name and email come from the prefill too.
        TestActors.as(TestActors.applicant(unique("tolu")));
        TestActors.profile(CallerProfile.empty());
        Response partial = post("/v1/applications/me", Map.of("kind", "SELLER",
                "prefill", Map.of("givenName", "Tolu", "familyName", "Bello", "email", "Tolu@Example.com",
                        "address", Map.of("line1", "1 Main St"))));
        assertThat(partial.status()).isEqualTo(201);
        assertThat(partial.text("/businessDetails/legalFirstName")).isEqualTo("Tolu");
        assertThat(partial.text("/business/contactEmail")).isEqualTo("tolu@example.com");
        assertThat(partial.text("/payouts/accountHolderName")).isEqualTo("Tolu Bello");
        assertThat(strings(partial.body().path("prefilledFields"))).containsExactlyInAnyOrder("business.contactEmail",
                "businessDetails.legalFirstName", "businessDetails.legalLastName", "payouts.accountHolderName");

        // Resuming fills answers that are still empty (the address saved since), never the ones given.
        Response resumed = post("/v1/applications/me", Map.of("kind", "SELLER", "prefill", Map.of("givenName", "Other",
                "address", Map.of("line1", "1 Main St", "city", "Houston", "state", "TX", "postalCode", "77002"))));
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.text("/businessDetails/legalFirstName")).isEqualTo("Tolu");
        assertThat(resumed.text("/businessDetails/address/postalCode")).isEqualTo("77002");
        assertThat(strings(resumed.body().path("prefilledFields"))).contains("businessDetails.address");
    }

    @Test
    void entityAwareDetailsOwnersAndRejection() {
        String user = unique("kwik");
        TestActors.as(TestActors.applicant(user));
        assertThat(post("/v1/applications/me", Map.of("kind", "PROVIDER")).status()).isEqualTo(201);
        assertThat(post("/v1/applications/me", Map.of("kind", "SELLER")).text("/code"))
                .isEqualTo("seller.application-exists");
        assertThat(put("/v1/applications/me/steps/business-details", Map.of("legalName", "Kwik")).text("/code"))
                .isEqualTo("seller.business-step-required");
        assertThat(put("/v1/applications/me/steps/business", Map.of("offering", "SERVICES",
                "entityType", "multi_member_llc", "country", "US")).status()).isEqualTo(200);
        Response ein = put("/v1/applications/me/steps/business-details", Map.of("legalName", "Kwik Cargo LLC",
                "taxId", "123456789", "stateOfFormation", "nj", "dateOfFormation", "2021-03-01"));
        assertThat(ein.status()).isEqualTo(200);
        assertThat(ein.text("/businessDetails/taxIdMasked")).isEqualTo("••-•••6789");
        assertThat(ein.text("/businessDetails/taxIdType")).isEqualTo("EIN");
        assertThat(ein.text("/businessDetails/stateOfFormation")).isEqualTo("NJ");

        Response tooMuch = put("/v1/applications/me/steps/owners", Map.of("owners", List.of(
                Map.of("firstName", "Kofi", "lastName", "Mensah", "ownershipPercent", 70, "significantControl", true),
                Map.of("firstName", "Ama", "lastName", "Mensah", "ownershipPercent", 40))));
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().path("fieldErrors").has("owners.ownershipTotal")).as(tooMuch.body().toString()).isTrue();
        Response owners = put("/v1/applications/me/steps/owners", Map.of("owners", List.of(
                Map.of("firstName", "Kofi", "lastName", "Mensah", "ownershipPercent", 60, "significantControl", true,
                        "dateOfBirth", "1980-02-02"),
                Map.of("firstName", "Ama", "lastName", "Mensah", "ownershipPercent", 40))));
        assertThat(owners.status()).isEqualTo(200);
        Response expired = put("/v1/applications/me/steps/catalog-compliance", Map.of("services", List.of(Map.of(
                "serviceName", "Freight forwarding", "regulated", true, "licenceNumber", "OTI-1",
                "issuingState", "NJ", "expiresOn", "2020-01-01", "documentAssetId", "asset-oti"))));
        assertThat(expired.status()).isEqualTo(422);
        assertThat(expired.body().path("fieldErrors").has("compliance.services[0].expiresOn")).isTrue();

        // Incomplete submission lists every missing field across steps.
        Response submit = post("/v1/applications/me/submit", Map.of());
        assertThat(submit.status()).isEqualTo(422);
        JsonNode errors = submit.body().path("fieldErrors");
        assertThat(errors.has("businessDetails.formationDocumentAssetId")).isTrue();
        assertThat(errors.has("stripe.account")).isTrue();
        assertThat(errors.has("plan.planCode")).isTrue();
    }

    private long accessCount(String appId, String kind) {
        return jdbc.sql("select count(*) from document_access_log where application_id = :a and kind = :k")
                .param("a", appId).param("k", kind).query(Long.class).single();
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
