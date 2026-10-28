package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.payments.application.ReferralProgram;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S322 against PostgreSQL: codes, attaching a friend at sign-up, the qualifying order (first one delivered with at
 * least the minimum value), the return window, the once-only grant through the credit ledger (source REFERRAL), abuse
 * checks and the staff review, caps, separate rewards per side, the kill switch and expiry.
 */
class ReferralIT extends PaymentsIntegrationTest {

    private static final String DEVICE_A = "a".repeat(64);
    private static final String DEVICE_B = "b".repeat(64);
    private static final String NET_A = "c".repeat(64);
    private static final Actor REVIEWER = new Actor("fin-review", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.finance"), Set.of("payments:read", "ledger:adjust"));
    private static final Actor CONFIGURER = new Actor("fin-config", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.finance"), Set.of("payments:read", "payments:configure"));
    private static final Actor READER = new Actor("fin-read", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.finance"), Set.of("payments:read"));

    ReferralProgram program;

    @BeforeEach
    void defaultSettings(@Autowired ReferralProgram referralProgram) {
        this.program = referralProgram;
        jdbc.sql("""
                update referral_settings set enabled = true, referrer_reward_type = 'CREDIT',
                    referrer_credit_minor = 1000, referrer_membership_days = 0, referred_reward_type = 'CREDIT',
                    referred_credit_minor = 1000, referred_membership_days = 0, min_order_minor = 3000,
                    return_window_days = 30, max_rewarded_per_referrer = 20, max_credit_per_customer_minor = 50000,
                    validity_days = 90 where id = 1
                """).update();
    }

    // ------------------------------------------------------------------ helpers

    private static Instant now() {
        return TestSupport.CLOCK.instant();
    }

    private String codeOf(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/referrals/me", null, null)).path("code").asString();
    }

    private void signUp(String friend, String code, String device, String net) {
        program.signedUp(new ReferralProgram.SignUp(friend, "Amara", "Okafor", "SHOP", code, device, net, now()));
    }

    private void order(String customer, String order, long total, String address, String... shipments) {
        List<ReferralProgram.Shipment> list = new ArrayList<>();
        for (String s : shipments) {
            list.add(new ReferralProgram.Shipment(s, total / shipments.length));
        }
        program.orderPlaced(new ReferralProgram.OrderPlaced(order, customer, total, now(), address, null, "77002",
                "US", list));
    }

    private void delivered(String customer, String order, String shipment) {
        program.shipmentEnded(new ReferralProgram.ShipmentEnded(shipment, order, customer, true, now()));
    }

    private void cancelled(String customer, String order, String shipment) {
        program.shipmentEnded(new ReferralProgram.ShipmentEnded(shipment, order, customer, false, now()));
    }

    private JsonNode referralOf(String friend) {
        TestSupport.as(REVIEWER);
        JsonNode list = ok(send("GET", "/v1/referrals?customerId=" + friend, null, null));
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private long balance(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/credit/me", null, null)).path("balanceMinor").asLong();
    }

    private long referralEntries(String customer) {
        return jdbc.sql("select count(*) from credit_entry where customer_id = :c and source = 'REFERRAL'")
                .param("c", customer).query(Long.class).single();
    }

    /** A referrer, a friend who signed up with the code, and the friend's delivered qualifying order. */
    private String[] delivered40(String referrer, String friend, String address) {
        String code = codeOf(referrer);
        signUp(friend, code, null, null);
        String order = id("ORD");
        order(friend, order, 4000, address, order + "-S1");
        delivered(friend, order, order + "-S1");
        return new String[] {code, order};
    }

    private Map<String, Object> settingsBody(JsonNode current, Map<String, Object> changes) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "enabled", current.path("enabled").asBoolean(),
                "referrer", Map.of("type", "CREDIT", "creditMinor", current.path("referrer").path("creditMinor").asLong(),
                        "membershipDays", 0),
                "referred", Map.of("type", "CREDIT", "creditMinor", current.path("referred").path("creditMinor").asLong(),
                        "membershipDays", 0),
                "qualifyingEvent", "FIRST_ORDER_DELIVERED",
                "minOrderMinor", current.path("minOrderMinor").asLong(),
                "returnWindowDays", current.path("returnWindowDays").asInt(),
                "maxRewardedPerReferrer", current.path("maxRewardedPerReferrer").asInt(),
                "maxCreditPerCustomerMinor", current.path("maxCreditPerCustomerMinor").asLong(),
                "validityDays", current.path("validityDays").asInt()));
        body.put("reason", "Test change of the referral program");
        body.put("version", current.path("version").asLong());
        body.putAll(changes);
        return body;
    }

    /** Reads the current settings, then saves them with {@code changes} as the configuring staff member. */
    private HttpResponse<String> put(Map<String, Object> changes) {
        JsonNode current = settings();
        TestSupport.as(CONFIGURER);
        return send("PUT", "/v1/referral-settings", settingsBody(current, changes), null);
    }

    private JsonNode settings() {
        TestSupport.as(READER);
        return ok(send("GET", "/v1/referral-settings", null, null));
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void codeIsStableAndUnguessableLooking() {
        String customer = id("cust");
        String code = codeOf(customer);
        assertThat(code).matches("[BCDFGHJKMNPQRSTVWXZ2-9]{8}");
        assertThat(codeOf(customer)).isEqualTo(code);
        TestSupport.as(TestSupport.customer(customer));
        JsonNode overview = ok(send("GET", "/v1/referrals/me", null, null));
        assertThat(overview.path("displayCode").asString()).isEqualTo(code.substring(0, 4) + "-" + code.substring(4));
        assertThat(overview.path("program").path("referrerReward").path("creditMinor").asLong()).isEqualTo(1000);
        assertThat(overview.path("program").path("minOrderMinor").asLong()).isEqualTo(3000);
        assertThat(overview.path("earnings").path("invited").asInt()).isZero();
        assertThat(codeOf(id("cust"))).isNotEqualTo(code);
    }

    @Test
    void bothSidesGetTenAfterTheFirstDeliveredOrderAndItsReturnWindow() {
        String referrer = id("referrer");
        String friend = id("friend");
        String[] first = delivered40(referrer, friend, "12 Main St");
        // the code may be typed in any case with a separator
        assertThat(first[0]).hasSize(8);
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("WAITING");
        assertThat(referral.path("qualifyingMinor").asLong()).isEqualTo(4000);
        assertThat(referral.path("referredName").asString()).isEqualTo("Amara O.");

        // still inside the 30-day return window
        TestSupport.CLOCK.advance(Duration.ofDays(29));
        program.grantDue(100);
        assertThat(balance(referrer)).isZero();
        assertThat(balance(friend)).isZero();

        TestSupport.CLOCK.advance(Duration.ofDays(2));
        assertThat(program.grantDue(100)).isGreaterThanOrEqualTo(1);
        assertThat(balance(referrer)).isEqualTo(1000);
        assertThat(balance(friend)).isEqualTo(1000);
        assertThat(referralOf(friend).path("status").asString()).isEqualTo("REWARDED");
        assertThat(referralOf(friend).path("costMinor").asLong()).isEqualTo(2000);

        // once per referral: another run, or a replayed delivery, grants nothing more
        program.grantDue(100);
        delivered(friend, first[1], first[1] + "-S1");
        program.grantDue(100);
        assertThat(referralEntries(referrer)).isEqualTo(1);
        assertThat(referralEntries(friend)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from credit_entry where idempotency_key in (:k)")
                .param("k", List.of("referral:" + referral.path("id").asString() + ":referrer",
                        "referral:" + referral.path("id").asString() + ":referred"))
                .query(Long.class).single()).isEqualTo(2);
        assertThat(outbox("payments.referral.rewarded", referrer)).isEqualTo(1);
        assertThat(outbox("payments.referral.rewarded", friend)).isEqualTo(1);

        // the program cost is booked as an expense against the credit liability
        assertThat(jdbc.sql("select count(*) from credit_entry where customer_id = :c and source = 'REFERRAL' "
                + "and kind = 'GRANT'").param("c", referrer).query(Long.class).single()).isEqualTo(1);
        assertEveryJournalBalances();

        // the customer's view: names, not ids, and no internals
        TestSupport.as(TestSupport.customer(referrer));
        JsonNode mine = ok(send("GET", "/v1/referrals/me/friends", null, null));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("friendName").asString()).isEqualTo("Amara O.");
        assertThat(mine.get(0).path("status").asString()).isEqualTo("REWARDED");
        assertThat(mine.get(0).path("reward").path("granted").asBoolean()).isTrue();
        assertThat(mine.get(0).path("reward").path("terms").path("creditMinor").asLong()).isEqualTo(1000);
        assertThat(mine.get(0).has("flags")).isFalse();
        assertThat(mine.get(0).has("referredId")).isFalse();
        JsonNode overview = ok(send("GET", "/v1/referrals/me", null, null));
        assertThat(overview.path("earnings").path("earnedMinor").asLong()).isEqualTo(1000);
        assertThat(overview.path("earnings").path("rewarded").asInt()).isEqualTo(1);
        TestSupport.as(TestSupport.customer(friend));
        JsonNode asFriend = ok(send("GET", "/v1/referrals/me", null, null)).path("asFriend");
        assertThat(asFriend.path("status").asString()).isEqualTo("REWARDED");
        assertThat(asFriend.path("reward").path("terms").path("creditMinor").asLong()).isEqualTo(1000);
    }

    @Test
    void codeTypedWithSeparatorAndLowerCaseAttaches() {
        String referrer = id("referrer");
        String friend = id("friend");
        String code = codeOf(referrer);
        signUp(friend, (code.substring(0, 4) + "-" + code.substring(4)).toLowerCase(Locale.ROOT), null, null);
        assertThat(referralOf(friend).path("referrerId").asString()).isEqualTo(referrer);
    }

    @Test
    void unknownMalformedAndOwnCodesAttachNothing() {
        String customer = id("self");
        String code = codeOf(customer);
        signUp(customer, code, null, null);
        signUp(id("f1"), "NOPE", null, null);
        signUp(id("f2"), "BBBBBBBB", null, null);
        signUp(id("f3"), null, null, null);
        TestSupport.as(REVIEWER);
        assertThat(ok(send("GET", "/v1/referrals?customerId=" + customer, null, null))).isEmpty();
    }

    @Test
    void aFriendIsReferredOnlyOnce() {
        String first = id("referrer");
        String second = id("referrer");
        String friend = id("friend");
        signUp(friend, codeOf(first), null, null);
        signUp(friend, codeOf(second), null, null);
        assertThat(referralOf(friend).path("referrerId").asString()).isEqualTo(first);
    }

    // ------------------------------------------------------------------ the qualifying order

    @Test
    void anOrderBelowTheMinimumDoesNotQualifyButALaterOneDoes() {
        String referrer = id("referrer");
        String friend = id("friend");
        signUp(friend, codeOf(referrer), null, null);
        String small = id("ORD");
        order(friend, small, 2000, "1 Elm St", small + "-S1");
        delivered(friend, small, small + "-S1");
        assertThat(referralOf(friend).path("status").asString()).isEqualTo("PENDING");

        String big = id("ORD");
        order(friend, big, 3000, "1 Elm St", big + "-S1");
        delivered(friend, big, big + "-S1");
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("WAITING");
        assertThat(referral.path("qualifyingOrderId").asString()).isEqualTo(big);
    }

    @Test
    void anOrderQualifiesOnlyWhenEveryShipmentEndedAndCountsWhatWasDelivered() {
        String referrer = id("referrer");
        String friend = id("friend");
        signUp(friend, codeOf(referrer), null, null);
        String o = id("ORD");
        order(friend, o, 8000, "2 Oak St", o + "-S1", o + "-S2");
        delivered(friend, o, o + "-S1");
        assertThat(referralOf(friend).path("status").asString()).as("one shipment is still on its way")
                .isEqualTo("PENDING");
        cancelled(friend, o, o + "-S2");
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("WAITING");
        assertThat(referral.path("qualifyingMinor").asLong()).as("only the delivered shipment counts").isEqualTo(4000);
    }

    @Test
    void aCancelledOrderNeverQualifies() {
        String referrer = id("referrer");
        String friend = id("friend");
        signUp(friend, codeOf(referrer), null, null);
        String o = id("ORD");
        order(friend, o, 8000, "3 Pine St", o + "-S1");
        cancelled(friend, o, o + "-S1");
        assertThat(referralOf(friend).path("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void theFriendHasLimitedTimeToQualify() {
        String referrer = id("referrer");
        String friend = id("friend");
        signUp(friend, codeOf(referrer), null, null);
        TestSupport.CLOCK.advance(Duration.ofDays(91));
        assertThat(program.expireStale(100)).isGreaterThanOrEqualTo(1);
        assertThat(referralOf(friend).path("status").asString()).isEqualTo("EXPIRED");
        // an order delivered afterwards cannot revive it
        String o = id("ORD");
        order(friend, o, 4000, "4 Birch St", o + "-S1");
        delivered(friend, o, o + "-S1");
        assertThat(referralOf(friend).path("status").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void aRefundBelowTheMinimumDuringTheReturnWindowEndsTheReferral() {
        String referrer = id("referrer");
        String friend = id("friend");
        String[] first = delivered40(referrer, friend, "5 Cedar St");
        // the order's payment was refunded in full during the return window
        createAuthorisedPayment(first[1], List.of(line(first[1] + "-L1", "org_ref", "Books", "SELF", 1, 4000, 0, 0)));
        jdbc.sql("update payment set captured_minor = amount_minor, refunded_minor = amount_minor "
                + "where order_id = :o").param("o", first[1]).update();
        TestSupport.CLOCK.advance(Duration.ofDays(31));
        program.grantDue(100);
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("REJECTED");
        assertThat(referral.path("decision").asString()).isEqualTo("SYSTEM");
        assertThat(balance(referrer)).isZero();
        assertThat(balance(friend)).isZero();
    }

    // ------------------------------------------------------------------ abuse checks

    @Test
    void sameDeviceHoldsTheReferralForStaffWhoApproveWithAReason() {
        String referrer = id("referrer");
        String friend = id("friend");
        program.signedUp(new ReferralProgram.SignUp(referrer, "Ada", "Obi", "SHOP", null, DEVICE_A, NET_A, now()));
        signUp(friend, codeOf(referrer), DEVICE_A, DEVICE_B);
        String o = id("ORD");
        order(friend, o, 4000, "6 Maple St", o + "-S1");
        delivered(friend, o, o + "-S1");
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(referral.path("flags").toString()).contains("SAME_DEVICE");

        // the customer just sees it waiting, with no mention of the check
        TestSupport.as(TestSupport.customer(referrer));
        JsonNode mine = ok(send("GET", "/v1/referrals/me/friends", null, null)).get(0);
        assertThat(mine.path("status").asString()).isEqualTo("WAITING");

        TestSupport.CLOCK.advance(Duration.ofDays(40));
        program.grantDue(100);
        assertThat(balance(referrer)).isZero();

        String id = referral.path("id").asString();
        TestSupport.as(REVIEWER);
        problem(send("POST", "/v1/referrals/" + id + "/approve", Map.of("reason", "too short"), null), 422,
                "payments.reason-required");
        JsonNode approved = ok(send("POST", "/v1/referrals/" + id + "/approve",
                Map.of("reason", "Two housemates on one laptop, verified by phone"), null));
        // the return window has passed, so the rewards are granted at once
        assertThat(approved.path("status").asString()).isEqualTo("REWARDED");
        assertThat(approved.path("decision").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("decidedBy").asString()).isEqualTo("fin-review");
        assertThat(balance(referrer)).isEqualTo(1000);
        assertThat(balance(friend)).isEqualTo(1000);
        assertThat(outbox("payments.referral.flagged", id)).isEqualTo(1);
        assertThat(outbox("payments.referral.decided", id)).isEqualTo(1);
    }

    @Test
    void sameDeliveryAddressIsFlaggedAndStaffCanReject() {
        String referrer = id("referrer");
        String friend = id("friend");
        String home = "77 Same Street";
        String mine = id("ORD");
        order(referrer, mine, 5000, home, mine + "-S1");
        delivered40(referrer, friend, home.toLowerCase(Locale.ROOT) + ".");
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(referral.path("flags").toString()).contains("SAME_ADDRESS");

        TestSupport.as(REVIEWER);
        JsonNode rejected = ok(send("POST", "/v1/referrals/" + referral.path("id").asString() + "/reject",
                Map.of("reason", "The same household created a second account"), null));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("decision").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("rewards").toString()).contains("VOID");
        TestSupport.CLOCK.advance(Duration.ofDays(40));
        program.grantDue(100);
        assertThat(balance(referrer)).isZero();
        assertThat(balance(friend)).isZero();
        // a decided referral cannot be decided again
        TestSupport.as(REVIEWER);
        assertThat(send("POST", "/v1/referrals/" + referral.path("id").asString() + "/approve",
                Map.of("reason", "Changed my mind about this one"), null).statusCode()).isEqualTo(409);
    }

    @Test
    void theSameSavedCardIsFlagged() {
        String referrer = id("referrer");
        String friend = id("friend");
        for (String customer : List.of(referrer, friend)) {
            jdbc.sql("insert into stripe_customer (customer_id, stripe_customer_id, created_at) values (:c, :s, now())")
                    .param("c", customer).param("s", "cus_" + customer).update();
            jdbc.sql("insert into customer_payment_method (id, customer_id, type, brand, last4, exp_month, exp_year, "
                    + "verified, created_at) values (:id, :c, 'card', 'visa', '4242', 12, 2034, true, now())")
                    .param("id", "pm_" + customer).param("c", customer).update();
        }
        delivered40(referrer, friend, "9 Walnut St");
        assertThat(referralOf(friend).path("flags").toString()).contains("SAME_PAYMENT_METHOD");
        assertThat(referralOf(friend).path("status").asString()).isEqualTo("IN_REVIEW");
    }

    // ------------------------------------------------------------------ caps

    @Test
    void aReferrerCappedAtOneRewardedReferralStillRewardsTheNextFriend() {
        ok(put(Map.of("maxRewardedPerReferrer", 1, "returnWindowDays", 0)));
        String referrer = id("referrer");
        String f1 = id("friend");
        String f2 = id("friend");
        delivered40(referrer, f1, "10 A St");
        delivered40(referrer, f2, "11 B St");
        program.grantDue(100);
        assertThat(balance(referrer)).as("only the first referral paid the referrer").isEqualTo(1000);
        assertThat(balance(f1)).isEqualTo(1000);
        assertThat(balance(f2)).as("the friend did nothing wrong").isEqualTo(1000);
        assertThat(referralOf(f2).path("rewards").toString()).contains("CAPPED");
    }

    @Test
    void creditCapPerCustomerLimitsWhatOneCustomerEarnsInAll() {
        ok(put(Map.of("maxCreditPerCustomerMinor", 1500, "returnWindowDays", 0)));
        String referrer = id("referrer");
        delivered40(referrer, id("friend"), "20 A St");
        delivered40(referrer, id("friend"), "21 B St");
        program.grantDue(100);
        assertThat(balance(referrer)).as("10.00 fits under 15.00, a second 10.00 does not").isEqualTo(1000);
    }

    // ------------------------------------------------------------------ settings

    @Test
    void eachSideIsConfiguredSeparatelyAndMembershipWaitsForTheMembershipProgram() {
        JsonNode before = settings();
        JsonNode saved = ok(put(Map.of("returnWindowDays", 0,
                "referrer", Map.of("type", "CREDIT", "creditMinor", 1500, "membershipDays", 0),
                "referred", Map.of("type", "MEMBERSHIP_DAYS", "creditMinor", 0, "membershipDays", 14))));
        assertThat(saved.path("referrer").path("creditMinor").asLong()).isEqualTo(1500);
        assertThat(saved.path("referred").path("type").asString()).isEqualTo("MEMBERSHIP_DAYS");
        assertThat(saved.path("version").asLong()).isEqualTo(before.path("version").asLong() + 1);

        String referrer = id("referrer");
        String friend = id("friend");
        delivered40(referrer, friend, "30 C St");
        program.grantDue(100);
        assertThat(balance(referrer)).isEqualTo(1500);
        assertThat(balance(friend)).as("a membership reward is not credit").isZero();
        JsonNode referral = referralOf(friend);
        assertThat(referral.path("status").asString()).isEqualTo("PARTIALLY_REWARDED");
        assertThat(referral.path("rewards").toString()).contains("DEFERRED").contains("GRANTED");
        // TODO(S323): until the membership program lands the days stay deferred, and retries never repeat the credit
        program.grantDue(100);
        assertThat(referralEntries(referrer)).isEqualTo(1);
    }

    @Test
    void settingsChangesNeedAReasonAndAnUpToDateVersionAndAreKeptInAHistory() {
        JsonNode current = settings();
        TestSupport.as(CONFIGURER);
        Map<String, Object> noReason = settingsBody(current, Map.of("reason", "short"));
        problem(send("PUT", "/v1/referral-settings", noReason, null), 422, "payments.reason-required");
        Map<String, Object> invalid = settingsBody(current, Map.of("validityDays", 0));
        problem(send("PUT", "/v1/referral-settings", invalid, null), 422, "payments.referral-settings-invalid");

        JsonNode saved = ok(send("PUT", "/v1/referral-settings", settingsBody(current, Map.of("minOrderMinor", 5000,
                "reason", "Raise the bar after the March promotion")), null));
        assertThat(saved.path("minOrderMinor").asLong()).isEqualTo(5000);
        assertThat(saved.path("updatedBy").asString()).isEqualTo("fin-config");
        // the editor still holds the old version
        problem(send("PUT", "/v1/referral-settings", settingsBody(current, Map.of("minOrderMinor", 6000)), null), 409,
                "payments.referral-settings-changed");
        JsonNode change = null;
        for (JsonNode c : ok(send("GET", "/v1/referral-settings/history?limit=200", null, null))) {
            if ("Raise the bar after the March promotion".equals(c.path("reason").asString())) {
                change = c;
            }
        }
        assertThat(change).as("the change is in the history").isNotNull();
        assertThat(change.path("changedBy").asString()).isEqualTo("fin-config");
        assertThat(change.path("before").asString()).contains("\"minOrderMinor\": 3000");
        assertThat(change.path("after").asString()).contains("\"minOrderMinor\": 5000");
    }

    @Test
    void whenTheProgramIsOffNoFriendIsAttachedAndNothingIsGranted() {
        String referrer = id("referrer");
        String waiting = id("friend");
        String code = codeOf(referrer);
        signUp(waiting, code, null, null);
        String o = id("ORD");
        order(waiting, o, 4000, "40 D St", o + "-S1");
        delivered(waiting, o, o + "-S1");
        ok(put(Map.of("enabled", false)));
        String late = id("friend");
        signUp(late, code, null, null);
        TestSupport.as(REVIEWER);
        assertThat(ok(send("GET", "/v1/referrals?customerId=" + late, null, null))).isEmpty();
        TestSupport.CLOCK.advance(Duration.ofDays(40));
        program.grantDue(100);
        assertThat(balance(referrer)).as("paused while the program is off").isZero();
        ok(put(Map.of("enabled", true)));
        program.grantDue(100);
        assertThat(balance(referrer)).isEqualTo(1000);
    }

    // ------------------------------------------------------------------ concurrency and access

    @Test
    void parallelRunsOfTheJobGrantEachRewardOnce() throws Exception {
        String referrer = id("referrer");
        String friend = id("friend");
        delivered40(referrer, friend, "50 E St");
        TestSupport.CLOCK.advance(Duration.ofDays(31));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                runs.add(pool.submit(() -> program.grantDue(500)));
            }
            for (Future<Integer> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(balance(referrer)).isEqualTo(1000);
        assertThat(balance(friend)).isEqualTo(1000);
        assertThat(referralEntries(referrer)).isEqualTo(1);
        assertThat(referralEntries(friend)).isEqualTo(1);
    }

    @Test
    void summaryShowsTheProgramCostAndStaffOnlyViewsAreForStaff() {
        TestSupport.as(READER);
        long before = ok(send("GET", "/v1/referrals/summary", null, null)).path("costMinor").asLong();
        String referrer = id("referrer");
        String friend = id("friend");
        delivered40(referrer, friend, "60 F St");
        TestSupport.CLOCK.advance(Duration.ofDays(31));
        program.grantDue(100);
        TestSupport.as(READER);
        JsonNode summary = ok(send("GET", "/v1/referrals/summary", null, null));
        assertThat(summary.path("costMinor").asLong()).isGreaterThanOrEqualTo(before + 2000);
        assertThat(summary.path("byStatus").path("REWARDED").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.path("enabled").asBoolean()).isTrue();

        TestSupport.as(TestSupport.customer(referrer));
        assertThat(send("GET", "/v1/referrals", null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/referrals/summary", null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/referral-settings", null, null).statusCode()).isEqualTo(403);
        String referralId = referralOf(friend).path("id").asString();
        JsonNode current = settings();
        TestSupport.as(READER);
        assertThat(send("GET", "/v1/referrals/me", null, null).statusCode()).as("not a customer").isEqualTo(403);
        assertThat(send("PUT", "/v1/referral-settings", settingsBody(current, Map.of()), null).statusCode())
                .isEqualTo(403);
        assertThat(send("POST", "/v1/referrals/" + referralId + "/reject",
                Map.of("reason", "A reason that is long enough"), null).statusCode()).as("needs ledger:adjust")
                .isEqualTo(403);
    }

    @Test
    void creditFromReferralsIsNeverCash() {
        // there is no operation that turns referral credit into money: the credit API has no cash-out path
        TestSupport.as(TestSupport.customer(id("cust")));
        assertThat(send("POST", "/v1/credit/me/cash-out", Map.of(), null).statusCode()).isIn(404, 405);
        assertThat(jdbc.sql("select count(*) from information_schema.columns where table_name like 'referral%' "
                + "and column_name in ('payout_id', 'transfer_id', 'stripe_payout_id')").query(Long.class).single())
                .isZero();
    }
}
