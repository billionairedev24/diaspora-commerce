package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.application.MembershipBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S323 against PostgreSQL and the Stripe stand-in: joining on a saved card with a trial and its abuse guard, renewal,
 * failed renewals that retry then lapse, upgrade, downgrade and cancel with the benefit running to the period end, and
 * the refund rule for the membership fee.
 */
class MembershipBillingIT extends MembershipSupport {

    private static final String JOIN = ".*:join:intent";
    private static final String UPGRADE = ".*:upgrade:.*:intent";
    private static final String RENEW = ".*:renew:[0-9]+:[0-9]:intent";

    MembershipBillingService billing;

    @BeforeEach
    void wire(@Autowired MembershipBillingService service) {
        this.billing = service;
    }

    private String freeDeliveryTier(int trialDays) {
        String code = tierCode("gold");
        tier(code, 10, 999, 9900, trialDays, List.of(benefit("FREE_DELIVERY", Map.of("minOrderMinor", 2500)),
                benefit("FREE_SHIPPING", Map.of("minOrderMinor", 2500))));
        return code;
    }

    private Instant at(JsonNode node) {
        return Instant.parse(node.asString());
    }

    // ------------------------------------------------------------------ join, trial, renewal

    @Test
    void aTrialChargesNothingThenTheFirstFeeIsTakenWhenItEnds() {
        String tier = freeDeliveryTier(14);
        String customer = shopperWithCard();

        JsonNode joined = join(customer, tier, "MONTHLY");
        assertThat(status(joined)).isEqualTo("TRIALING");
        assertThat(joined.path("charges")).isEmpty();
        assertThat(joined.path("tier").path("name").asString()).isEqualTo(tier.toUpperCase());
        Instant trialEnd = at(joined.path("membership").path("trialEnd"));
        assertThat(Duration.between(Instant.now(), trialEnd).toDays()).isBetween(13L, 14L);
        assertThat(joined.path("card").path("last4").asString()).hasSize(4);
        assertThat(entitlements(customer).path("member").asBoolean()).isTrue();

        // nothing is due before the trial ends
        assertThat(billing.renewDue(500)).isGreaterThanOrEqualTo(0);
        assertThat(status(me(customer))).isEqualTo("TRIALING");

        chargeWorks(customer, RENEW, 999);
        TestSupport.CLOCK.advance(Duration.ofDays(14).plusHours(1));
        assertThat(billing.renewDue(500)).isGreaterThanOrEqualTo(1);

        JsonNode renewed = me(customer);
        assertThat(status(renewed)).isEqualTo("ACTIVE");
        assertThat(renewed.path("charges")).hasSize(1);
        assertThat(renewed.path("charges").get(0).path("kind").asString()).isEqualTo("RENEWAL");
        assertThat(renewed.path("charges").get(0).path("amountMinor").asLong()).isEqualTo(999);
        assertThat(at(renewed.path("membership").path("periodStart"))).isEqualTo(trialEnd);
        assertThat(at(renewed.path("membership").path("periodEnd"))).isAfter(trialEnd.plus(Duration.ofDays(27)));

        // the fee is revenue (Dr 1000 / Dr 5000 / Cr 4700) and every journal still balances
        long revenue = jdbc.sql("""
                select coalesce(sum(jl.amount_minor), 0) from journal_entry j join journal_line jl on jl.journal_id = j.id
                where j.type = 'MEMBERSHIP_CHARGE' and jl.side = 'C' and j.description like :d
                """).param("d", "%" + customer + "%").query(Long.class).single();
        assertThat(revenue).isEqualTo(999);
        assertEveryJournalBalances();
    }

    @Test
    void aTrialIsForFirstTimeMembersAndForCardsThatNeverHadOne() {
        String tier = freeDeliveryTier(14);
        String first = shopperWithCard();
        assertThat(status(join(first, tier, "MONTHLY"))).isEqualTo("TRIALING");

        // a second account on the same card gets no trial: the first period is charged now
        String second = id("cust");
        sameCardAs(second, first);
        chargeWorks(second, JOIN, 999);
        JsonNode paid = join(second, tier, "MONTHLY");
        assertThat(status(paid)).isEqualTo("ACTIVE");
        assertThat(paid.path("charges")).hasSize(1);
        assertThat(paid.path("charges").get(0).path("kind").asString()).isEqualTo("JOIN");

        // leaving and coming back does not start another trial
        cancel(first, false);
        TestSupport.CLOCK.advance(Duration.ofDays(15));
        billing.renewDue(500);
        assertThat(status(me(first))).isEqualTo("CANCELED");
        assertThat(me(first).path("trialAvailable").asBoolean()).isFalse();
        chargeWorks(first, JOIN, 999);
        JsonNode back = join(first, tier, "MONTHLY");
        assertThat(status(back)).isEqualTo("ACTIVE");
        assertThat(back.path("charges")).hasSize(1);

        // a new shopper on a new card does get the trial
        assertThat(status(join(shopperWithCard(), tier, "MONTHLY"))).isEqualTo("TRIALING");
    }

    @Test
    void aDeclinedCardOnJoiningLeavesNoMembership() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeDeclined(customer, JOIN, 999);
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/memberships/me/join", Map.of("tierCode", tier, "interval", "MONTHLY"), "join-1"),
                422, "payments.membership-charge-failed");
        assertThat(membershipCount(customer)).isZero();
        assertThat(entitlements(customer).path("member").asBoolean()).isFalse();
    }

    @Test
    void joiningNeedsASavedCardAndAnIdempotencyKeyAndAPublishedTier() {
        String tier = freeDeliveryTier(0);
        TestSupport.as(TestSupport.customer(id("nocard")));
        problem(send("POST", "/v1/memberships/me/join", Map.of("tierCode", tier, "interval", "MONTHLY"), "k1"), 422,
                "payments.no-saved-method");
        String customer = shopperWithCard();
        TestSupport.as(TestSupport.customer(customer));
        assertThat(send("POST", "/v1/memberships/me/join", Map.of("tierCode", tier, "interval", "MONTHLY"), null)
                .statusCode()).as("the Idempotency-Key header is required").isEqualTo(400);
        problem(send("POST", "/v1/memberships/me/join", Map.of("tierCode", "nope", "interval", "MONTHLY"), "k2"), 404,
                "payments.membership-tier-not-found");
    }

    @Test
    void joiningTwiceWithTheSameKeyChargesOnceAndAMemberCannotJoinAgain() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        TestSupport.as(TestSupport.customer(customer));
        Map<String, Object> body = Map.of("tierCode", tier, "interval", "MONTHLY");
        ok(send("POST", "/v1/memberships/me/join", body, "same-key"), 201);
        ok(send("POST", "/v1/memberships/me/join", body, "same-key"), 201);
        assertThat(me(customer).path("charges")).hasSize(1);
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/memberships/me/join", body, "another-key"), 409, "payments.membership-exists");
    }

    // ------------------------------------------------------------------ failed renewals

    @Test
    void failedRenewalsRetryOnTheScheduleThenTheMembershipLapses() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        join(customer, tier, "MONTHLY");
        Instant periodEnd = at(me(customer).path("membership").path("periodEnd"));

        chargeDeclined(customer, RENEW, 999);
        TestSupport.CLOCK.setNow(periodEnd.plusSeconds(60));
        billing.renewDue(500);
        JsonNode pastDue = me(customer);
        assertThat(status(pastDue)).isEqualTo("PAST_DUE");
        assertThat(pastDue.path("membership").path("retryCount").asInt()).isEqualTo(1);
        assertThat(Duration.between(periodEnd.plusSeconds(60).plus(Duration.ofDays(1)),
                at(pastDue.path("membership").path("nextRetryAt"))).abs()).isLessThan(Duration.ofSeconds(5));
        assertThat(entitlements(customer).path("member").asBoolean()).as("grace keeps the benefits").isTrue();

        // not before the retry day
        TestSupport.CLOCK.setNow(periodEnd.plus(Duration.ofHours(12)));
        billing.renewDue(500);
        assertThat(me(customer).path("membership").path("retryCount").asInt()).isEqualTo(1);

        // retries on day 1, +3 and +5, then it lapses
        Instant now = periodEnd.plusSeconds(60);
        for (int days : new int[] {1, 3, 5}) {
            now = now.plus(Duration.ofDays(days)).plusSeconds(1);
            TestSupport.CLOCK.setNow(now);
            billing.renewDue(500);
        }
        JsonNode lapsed = me(customer);
        assertThat(status(lapsed)).isEqualTo("LAPSED");
        assertThat(lapsed.path("membership").path("endReason").asString()).isEqualTo("PAYMENT_FAILED");
        assertThat(entitlements(customer).path("member").asBoolean()).isFalse();
        assertThat(lapsed.path("charges").size()).isEqualTo(5); // the join and four failed attempts
        long failed = jdbc.sql("select count(*) from membership_charge where customer_id = :c and status = 'FAILED'")
                .param("c", customer).query(Long.class).single();
        assertThat(failed).isEqualTo(4);
        assertEveryJournalBalances();
    }

    @Test
    void aRetryThatSucceedsRestoresTheMembership() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        join(customer, tier, "MONTHLY");
        Instant periodEnd = at(me(customer).path("membership").path("periodEnd"));

        chargeDeclined(customer, ".*:renew:[0-9]+:1:intent", 999);
        TestSupport.CLOCK.setNow(periodEnd.plusSeconds(60));
        billing.renewDue(500);
        assertThat(status(me(customer))).isEqualTo("PAST_DUE");

        chargeWorks(customer, ".*:renew:[0-9]+:2:intent", 999);
        TestSupport.CLOCK.setNow(periodEnd.plus(Duration.ofDays(1)).plusSeconds(120));
        billing.renewDue(500);
        JsonNode back = me(customer);
        assertThat(status(back)).isEqualTo("ACTIVE");
        assertThat(back.path("membership").path("retryCount").asInt()).isZero();
        assertThat(at(back.path("membership").path("periodStart"))).isEqualTo(periodEnd);
    }

    // ------------------------------------------------------------------ upgrade, downgrade, cancel

    @Test
    void anUpgradeIsImmediateAndChargesTheDifferenceForTheRestOfThePeriod() {
        String basic = tierCode("basic");
        String pro = tierCode("pro");
        tier(basic, 10, 1000, 10000, 0, List.of(benefit("MEMBER_PRICES")));
        tier(pro, 20, 2000, 20000, 0, List.of(benefit("MEMBER_PRICES"), benefit("PRIORITY_SUPPORT")));
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 1000);
        join(customer, basic, "MONTHLY");
        JsonNode before = me(customer);
        Instant start = at(before.path("membership").path("periodStart"));
        Instant end = at(before.path("membership").path("periodEnd"));

        TestSupport.CLOCK.setNow(start.plus(Duration.between(start, end).dividedBy(2)));
        chargeWorks(customer, UPGRADE, 500);
        JsonNode upgraded = change(customer, pro, "MONTHLY");

        assertThat(upgraded.path("membership").path("tierCode").asString()).isEqualTo(pro);
        assertThat(upgraded.path("membership").path("priceMinor").asLong()).isEqualTo(2000);
        assertThat(at(upgraded.path("membership").path("periodEnd"))).as("the paid period is kept").isEqualTo(end);
        JsonNode topUp = upgraded.path("charges").get(0);
        assertThat(topUp.path("kind").asString()).isEqualTo("UPGRADE");
        assertThat(topUp.path("amountMinor").asLong()).as("half the difference").isBetween(495L, 505L);
        assertThat(entitlements(customer).path("prioritySupport").asBoolean()).isTrue();
        assertEveryJournalBalances();
    }

    @Test
    void aDowngradeWaitsForThePeriodEndAndKeepsTheBenefitsMeanwhile() {
        String basic = tierCode("basic");
        String pro = tierCode("pro");
        tier(basic, 10, 1000, 10000, 0, List.of(benefit("MEMBER_PRICES")));
        tier(pro, 20, 2000, 20000, 0, List.of(benefit("MEMBER_PRICES"), benefit("PRIORITY_SUPPORT")));
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 2000);
        join(customer, pro, "MONTHLY");

        JsonNode scheduled = change(customer, basic, "MONTHLY");
        assertThat(scheduled.path("membership").path("tierCode").asString()).isEqualTo(pro);
        assertThat(scheduled.path("membership").path("pendingTierCode").asString()).isEqualTo(basic);
        assertThat(scheduled.path("pendingTier").path("code").asString()).isEqualTo(basic);
        assertThat(scheduled.path("charges")).hasSize(1);
        assertThat(entitlements(customer).path("prioritySupport").asBoolean()).as("pro to the period end").isTrue();

        // choosing the current plan again takes the change back
        JsonNode kept = change(customer, pro, "MONTHLY");
        assertThat(kept.path("membership").path("pendingTierCode").isNull()).isTrue();
        change(customer, basic, "MONTHLY");

        Instant end = at(me(customer).path("membership").path("periodEnd"));
        chargeWorks(customer, RENEW, 1000);
        TestSupport.CLOCK.setNow(end.plusSeconds(60));
        billing.renewDue(500);
        JsonNode renewed = me(customer);
        assertThat(renewed.path("membership").path("tierCode").asString()).isEqualTo(basic);
        assertThat(renewed.path("membership").path("priceMinor").asLong()).isEqualTo(1000);
        assertThat(renewed.path("charges").get(0).path("amountMinor").asLong()).isEqualTo(1000);
        assertThat(entitlements(customer).path("prioritySupport").asBoolean()).isFalse();
    }

    @Test
    void cancelingKeepsTheBenefitsToThePeriodEndAndChargesNothingMore() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        join(customer, tier, "MONTHLY");
        Instant end = at(me(customer).path("membership").path("periodEnd"));

        JsonNode canceled = cancel(customer, false);
        assertThat(canceled.path("membership").path("cancelAtPeriodEnd").asBoolean()).isTrue();
        assertThat(status(canceled)).isEqualTo("ACTIVE");
        assertThat(entitlements(customer).path("member").asBoolean()).isTrue();

        // taking it back before the end keeps the membership renewing
        TestSupport.as(TestSupport.customer(customer));
        JsonNode resumed = ok(send("POST", "/v1/memberships/me/resume", null, null));
        assertThat(resumed.path("membership").path("cancelAtPeriodEnd").asBoolean()).isFalse();
        cancel(customer, false);

        TestSupport.CLOCK.setNow(end.plusSeconds(60));
        billing.renewDue(500);
        JsonNode over = me(customer);
        assertThat(status(over)).isEqualTo("CANCELED");
        assertThat(over.path("membership").path("endReason").asString()).isEqualTo("CANCELED");
        assertThat(over.path("charges")).as("no renewal charge").hasSize(1);
        assertThat(entitlements(customer).path("member").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ the fee refund rule

    @Test
    void cancelingWithinTheWindowBeforeUsingABenefitRefundsTheFeeToTheCard() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        JsonNode joined = join(customer, tier, "MONTHLY");
        assertThat(joined.path("refund").path("eligible").asBoolean()).isTrue();
        assertThat(joined.path("refund").path("amountMinor").asLong()).isEqualTo(999);

        JsonNode refunded = cancel(customer, true);
        assertThat(status(refunded)).isEqualTo("CANCELED");
        assertThat(refunded.path("membership").path("endReason").asString()).isEqualTo("REFUNDED");
        assertThat(refunded.path("charges").get(0).path("status").asString()).isEqualTo("REFUNDED");
        assertThat(refunded.path("charges").get(0).path("refundedMinor").asLong()).isEqualTo(999);
        assertThat(entitlements(customer).path("member").asBoolean()).isFalse();
        long reversed = jdbc.sql("""
                select coalesce(sum(jl.amount_minor), 0) from journal_entry j join journal_line jl on jl.journal_id = j.id
                where j.type = 'MEMBERSHIP_REFUND' and jl.side = 'D' and j.description like :d
                """).param("d", "%" + customer + "%").query(Long.class).single();
        assertThat(reversed).isEqualTo(999);
        assertEveryJournalBalances();
    }

    @Test
    void theFeeIsNotRefundedOnceABenefitWasUsedOrTheWindowPassed() {
        String tier = freeDeliveryTier(0);

        String used = shopperWithCard();
        chargeWorks(used, JOIN, 999);
        join(used, tier, "MONTHLY");
        jdbc.sql("""
                insert into membership_benefit_use (id, membership_id, customer_id, tier_code, benefit, order_id, ref,
                    amount_minor, currency, created_at)
                select gen_random_uuid(), id, customer_id, tier_code, 'FREE_DELIVERY', 'order-' || customer_id, 'ship-1', 799,
                    'USD', now() from membership where customer_id = :c
                """).param("c", used).update();
        TestSupport.as(TestSupport.customer(used));
        assertThat(me(used).path("refund").path("reason").asString()).isEqualTo("BENEFIT_USED");
        problem(send("POST", "/v1/memberships/me/cancel", Map.of("refund", true), null), 409,
                "payments.membership-refund-not-eligible");
        assertThat(cancel(used, false).path("membership").path("cancelAtPeriodEnd").asBoolean()).isTrue();

        String late = shopperWithCard();
        chargeWorks(late, JOIN, 999);
        join(late, tier, "MONTHLY");
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        assertThat(me(late).path("refund").path("reason").asString()).isEqualTo("WINDOW_PASSED");
        TestSupport.as(TestSupport.customer(late));
        problem(send("POST", "/v1/memberships/me/cancel", Map.of("refund", true), null), 409,
                "payments.membership-refund-not-eligible");
    }

    @Test
    void staffCanRefundAFeeWithAReasonAndTheMembershipEnds() {
        String tier = freeDeliveryTier(0);
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        String charge = join(customer, tier, "MONTHLY").path("charges").get(0).path("id").asString();

        TestSupport.as(TestSupport.SUPER_ADMIN);
        problem(send("POST", "/v1/memberships/admin/charges/" + charge + "/refund", Map.of("reason", "short"), null),
                422, "payments.reason-required");
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/memberships/admin/charges/" + charge + "/refund",
                Map.of("reason", "I would like my money back please"), null), 403, "auth.missing-permission");
        TestSupport.as(TestSupport.SUPER_ADMIN);
        JsonNode refunded = ok(send("POST", "/v1/memberships/admin/charges/" + charge + "/refund",
                Map.of("reason", "Charged twice by mistake, support case 4411"), null));
        assertThat(refunded.path("status").asString()).isEqualTo("REFUNDED");
        assertThat(status(me(customer))).isEqualTo("CANCELED");
        TestSupport.as(TestSupport.SUPER_ADMIN);
        problem(send("POST", "/v1/memberships/admin/charges/" + charge + "/refund",
                Map.of("reason", "Trying to refund the same fee twice"), null), 409,
                "payments.membership-charge-not-refundable");
    }
}
