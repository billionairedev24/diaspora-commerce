package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.payments.application.MembershipBillingService;
import com.afrotransact.payments.application.MembershipGrants;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S323 benefits against PostgreSQL: the delivery fee waivers (order minimum, per-order cap, recorded on the order's
 * payment), cashback on delivered orders (once per order, never in a trial), the monthly credit, entitlements for other
 * services and the free days programs grant.
 */
class MembershipBenefitsIT extends MembershipSupport {

    private static final String JOIN = ".*:join:intent";
    private static final String RENEW = ".*:renew:[0-9]+:[0-9]:intent";
    private static final Actor PROGRAM = new Actor("svc-referral", PrincipalType.SERVICE, Optional.empty(), Set.of(),
            Set.of("payments:credit.grant"));

    MembershipBillingService billing;
    MembershipGrants grants;

    @BeforeEach
    void wire(@Autowired MembershipBillingService service, @Autowired MembershipGrants memberGrants) {
        this.billing = service;
        this.grants = memberGrants;
    }

    /** A paying member (no trial) of a fresh tier. */
    private String member(String tier) {
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 999);
        join(customer, tier, "MONTHLY");
        return customer;
    }

    private JsonNode waivers(String customer, long items, List<Map<String, Object>> fees) {
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("POST", "/v1/memberships/waivers", Map.of("customerId", customer, "itemsMinor", items,
                "currency", "USD", "fees", fees), null));
    }

    private static Map<String, Object> fee(String key, String kind, long feeMinor) {
        return Map.of("key", key, "kind", kind, "feeMinor", feeMinor);
    }

    private long balance(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/credit/me", null, null)).path("balanceMinor").asLong();
    }

    private JsonNode creditEntries(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/credit/me/entries?limit=100", null, null));
    }

    // ------------------------------------------------------------------ fee waivers

    @Test
    void membersHaveTheirDeliveryFeesWaivedAboveTheMinimumUpToTheCap() {
        String tier = tierCode("silver");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("FREE_DELIVERY", Map.of("minOrderMinor", 2500, "capMinor", 1000)),
                benefit("FREE_SHIPPING", Map.of("minOrderMinor", 4000))));
        String customer = member(tier);
        List<Map<String, Object>> fees = List.of(fee("s1", "AFROTRANSACT_DELIVERY", 799),
                fee("s2", "AFROTRANSACT_DELIVERY", 799), fee("s3", "CARRIER", 599), fee("s4", "PICKUP", 100));

        JsonNode quote = waivers(customer, 3000, fees);
        assertThat(quote.path("member").asBoolean()).isTrue();
        assertThat(quote.path("tierName").asString()).isEqualTo(tier.toUpperCase());
        assertThat(quote.path("waivers")).as("delivery up to the 10.00 cap; shipping needs 40.00").hasSize(2);
        assertThat(quote.path("waivers").get(0).path("benefit").asString()).isEqualTo("FREE_DELIVERY");
        assertThat(quote.path("waivers").get(0).path("waivedMinor").asLong()).isEqualTo(799);
        assertThat(quote.path("waivers").get(1).path("waivedMinor").asLong()).isEqualTo(201);
        assertThat(quote.path("totalWaivedMinor").asLong()).isEqualTo(1000);

        JsonNode more = waivers(customer, 4000, fees);
        assertThat(more.path("totalWaivedMinor").asLong()).as("the carrier fee is covered at 40.00").isEqualTo(1599);

        assertThat(waivers(customer, 2000, fees).path("waivers")).as("under every minimum").isEmpty();
    }

    @Test
    void theQuoteShowsTheCashbackAPayingMemberWillEarnOnThisOrder() {
        String tier = tierCode("earn");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("CASHBACK", Map.of("percentBps", 500, "minOrderMinor", 2500,
                "capMinor", 200))));
        String customer = member(tier);
        List<Map<String, Object>> noFees = List.of();
        assertThat(waivers(customer, 3000, noFees).path("cashbackMinor").asLong()).as("5% of 30.00").isEqualTo(150);
        assertThat(waivers(customer, 5000, noFees).path("cashbackMinor").asLong()).as("capped at 2.00").isEqualTo(200);
        assertThat(waivers(customer, 2000, noFees).path("cashbackMinor").asLong()).as("under the minimum").isZero();
        assertThat(waivers(shopperWithCard(), 3000, noFees).path("cashbackMinor").asLong()).as("a non-member").isZero();

        String trialTier = tierCode("trialearn");
        tier(trialTier, 20, 999, 9900, 14, List.of(benefit("CASHBACK", Map.of("percentBps", 500))));
        String trialling = shopperWithCard();
        join(trialling, trialTier, "MONTHLY");
        assertThat(waivers(trialling, 3000, noFees).path("cashbackMinor").asLong())
                .as("credit benefits start with the first paid period").isZero();
    }

    @Test
    void aShopperWhoIsNotAMemberSeesWhatJoiningWouldSave() {
        String customer = shopperWithCard();
        JsonNode none = waivers(customer, 5000, List.of(fee("s1", "AFROTRANSACT_DELIVERY", 799)));
        assertThat(none.path("member").asBoolean()).isFalse();
        assertThat(none.path("waivers")).isEmpty();
        assertThat(none.path("upsell").isNull() || none.path("upsell").isMissingNode())
                .as("services get no upsell").isTrue();

        TestSupport.as(TestSupport.customer(customer));
        JsonNode mine = ok(send("POST", "/v1/memberships/me/savings", Map.of("itemsMinor", 5000, "currency", "USD",
                "fees", List.of(fee("s1", "AFROTRANSACT_DELIVERY", 799), fee("s2", "CARRIER", 599))), null));
        assertThat(mine.path("member").asBoolean()).isFalse();
        JsonNode upsell = mine.path("upsell");
        assertThat(upsell.path("tierName").asString()).isNotBlank();
        assertThat(upsell.path("savingMinor").asLong()).as("delivery alone, or delivery and shipping")
                .isIn(799L, 1398L);
        assertThat(upsell.path("monthlyPriceMinor").asLong()).isPositive();

        TestSupport.as(TestSupport.customer(customer));
        JsonNode noFees = ok(send("POST", "/v1/memberships/me/savings", Map.of("itemsMinor", 5000, "currency", "USD",
                "fees", List.of(fee("s1", "PICKUP", 0))), null));
        assertThat(noFees.path("upsell").isNull() || noFees.path("upsell").isMissingNode())
                .as("nothing to offer when there is no fee to save").isTrue();
    }

    @Test
    void theWaivedFeesAreRecordedWithTheOrdersPaymentAsBenefitsUsed() {
        String tier = tierCode("silver");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("FREE_DELIVERY", Map.of("minOrderMinor", 2500))));
        String customer = member(tier);
        String order = id("ORD");

        STRIPE.createIntent(order, "pi_" + order, 3000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        Map<String, Object> body = Map.of("orderId", order, "customerId", "cus_x", "amountMinor", 3000, "currency",
                "USD", "shopperId", customer, "lines", List.of(line("L1", "org_m", "Books", "SELF", 1, 3000, 0, 0)),
                "membershipWaivers", List.of(Map.of("ref", "ship-1", "benefit", "FREE_DELIVERY", "amountMinor", 799,
                        "tierCode", tier)));
        ok(send("POST", "/v1/payments", body, "create-" + order), 201);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments", body, "create-" + order), 200);

        TestSupport.as(TestSupport.customer(customer));
        JsonNode uses = ok(send("GET", "/v1/memberships/me/uses", null, null));
        assertThat(uses).hasSize(1);
        assertThat(uses.get(0).path("benefit").asString()).isEqualTo("FREE_DELIVERY");
        assertThat(uses.get(0).path("orderId").asString()).isEqualTo(order);
        assertThat(uses.get(0).path("amountMinor").asLong()).isEqualTo(799);
        JsonNode usage = me(customer).path("usage");
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).path("amountMinor").asLong()).isEqualTo(799);
        // a benefit was used, so the fee can no longer be refunded
        assertThat(me(customer).path("refund").path("reason").asString()).isEqualTo("BENEFIT_USED");
    }

    // ------------------------------------------------------------------ credit benefits

    private void deliveredOrder(String order, String customer, long items) {
        STRIPE.createIntent(order, "pi_" + order, items);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_x", "amountMinor", items,
                "currency", "USD", "shopperId", customer, "lines", List.of(line("L1", "org_m", "Books", "SELF", 1,
                items, 0, 0))), "create-" + order), 201);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of("kind", "PRODUCT"), null));
    }

    @Test
    void cashbackIsGrantedOncePerDeliveredOrderThroughTheCreditLedger() {
        String tier = tierCode("cash");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("CASHBACK", Map.of("percentBps", 500, "capMinor", 300,
                "minOrderMinor", 1000))));
        String customer = member(tier);

        String order = id("ORD");
        deliveredOrder(order, customer, 4000);
        assertThat(balance(customer)).as("5% of 40.00 is 2.00").isEqualTo(200);
        JsonNode entry = creditEntries(customer).get(0);
        assertThat(entry.path("source").asString()).isEqualTo("MEMBERSHIP");
        assertThat(entry.path("kind").asString()).isEqualTo("GRANT");
        assertThat(entry.path("orderId").asString()).isEqualTo(order);

        // the delivery notice again, and the sweep, change nothing
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of("kind", "PRODUCT"), null));
        billing.grantDueCashback(200);
        assertThat(balance(customer)).isEqualTo(200);

        // the cap, and an order under the minimum
        String big = id("ORD");
        deliveredOrder(big, customer, 20_000);
        assertThat(balance(customer)).as("5% of 200.00 is capped at 3.00").isEqualTo(500);
        deliveredOrder(id("ORD"), customer, 900);
        assertThat(balance(customer)).isEqualTo(500);

        // it is a cost of the program, booked Dr 5200 / Cr 2600, and a use of the benefit
        TestSupport.as(TestSupport.customer(customer));
        assertThat(ok(send("GET", "/v1/memberships/me/uses", null, null))).hasSize(2);
        assertEveryJournalBalances();
    }

    @Test
    void theSweepGrantsCashbackTheDeliveryHookMissed() {
        String tier = tierCode("cash");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("CASHBACK", Map.of("percentBps", 1000))));
        String customer = member(tier);
        String order = id("ORD");
        STRIPE.createIntent(order, "pi_" + order, 3000);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments", Map.of("orderId", order, "customerId", "cus_x", "amountMinor", 3000,
                "currency", "USD", "shopperId", customer, "lines", List.of(line("L1", "org_m", "Books", "SELF", 1,
                3000, 0, 0))), "create-" + order), 201);
        // delivered, but the hook never ran
        jdbc.sql("""
                insert into settlement (order_id, kind, delivered_at, release_at, status, next_check_at, updated_at)
                values (:o, 'PRODUCT', now() + interval '1 minute', now() + interval '7 days', 'OPEN',
                        now() + interval '7 days', now())
                """).param("o", order).update();
        TestSupport.CLOCK.advance(Duration.ofMinutes(5));
        assertThat(billing.grantDueCashback(200)).isGreaterThanOrEqualTo(1);
        assertThat(balance(customer)).isEqualTo(300);
    }

    @Test
    void creditBenefitsWaitForTheFirstPaidPeriodSoATrialEarnsNoCredit() {
        String tier = tierCode("trial");
        tier(tier, 10, 999, 9900, 14, List.of(benefit("CASHBACK", Map.of("percentBps", 500)),
                benefit("MONTHLY_CREDIT", Map.of("amountMinor", 500)), benefit("PRIORITY_SUPPORT")));
        String customer = shopperWithCard();
        assertThat(join(customer, tier, "MONTHLY").path("membership").path("status").asString())
                .isEqualTo("TRIALING");

        deliveredOrder(id("ORD"), customer, 4000);
        assertThat(balance(customer)).isZero();
        JsonNode e = entitlements(customer);
        assertThat(e.path("member").asBoolean()).isTrue();
        assertThat(e.path("prioritySupport").asBoolean()).isTrue();
        assertThat(e.path("benefits").toString()).doesNotContain("CASHBACK").doesNotContain("MONTHLY_CREDIT");

        // the trial turns into a paid period and the credit benefits start
        chargeWorks(customer, RENEW, 999);
        TestSupport.CLOCK.advance(Duration.ofDays(14).plusHours(1));
        billing.renewDue(500);
        assertThat(balance(customer)).as("the monthly credit of the first paid period").isEqualTo(500);
        assertThat(entitlements(customer).path("benefits").toString()).contains("CASHBACK");
    }

    @Test
    void theMonthlyCreditIsGrantedOncePerMonthOfTheMembership() {
        String tier = tierCode("monthly");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("MONTHLY_CREDIT", Map.of("amountMinor", 700))));
        String customer = member(tier);
        assertThat(balance(customer)).as("the first month's credit comes with joining").isEqualTo(700);
        billing.grantMonthlyCredits(1000);
        billing.grantMonthlyCredits(1000);
        assertThat(balance(customer)).isEqualTo(700);

        TestSupport.CLOCK.advance(Duration.ofDays(32));
        assertThat(billing.grantMonthlyCredits(1000)).isGreaterThanOrEqualTo(1);
        billing.grantMonthlyCredits(1000);
        assertThat(balance(customer)).isEqualTo(1400);
        assertThat(creditEntries(customer).get(0).path("source").asString()).isEqualTo("MEMBERSHIP");
    }

    // ------------------------------------------------------------------ entitlements

    @Test
    void entitlementsTellOtherServicesWhatTheMemberHas() {
        String tier = tierCode("max");
        tier(tier, 30, 1999, 19900, 0, List.of(benefit("EARLY_ACCESS", Map.of("hours", 24)),
                benefit("PRIORITY_SUPPORT"), benefit("MEMBER_PRICES"),
                benefit("BOOSTED_REWARDS", Map.of("percentBps", 5000))));
        String customer = shopperWithCard();
        chargeWorks(customer, JOIN, 1999);
        join(customer, tier, "YEARLY");
        JsonNode e = entitlements(customer);
        assertThat(e.path("member").asBoolean()).isTrue();
        assertThat(e.path("tierName").asString()).isEqualTo(tier.toUpperCase());
        assertThat(e.path("earlyAccessHours").asInt()).isEqualTo(24);
        assertThat(e.path("prioritySupport").asBoolean()).isTrue();
        assertThat(e.path("memberPrices").asBoolean()).isTrue();
        assertThat(e.path("rewardBoostBps").asInt()).isEqualTo(5000);

        TestSupport.as(TestSupport.customer(customer));
        assertThat(ok(send("GET", "/v1/memberships/me/entitlements", null, null)).path("member").asBoolean())
                .isTrue();
        JsonNode stranger = entitlements(id("nobody"));
        assertThat(stranger.path("member").asBoolean()).isFalse();
        assertThat(stranger.path("earlyAccessHours").asInt()).isZero();
    }

    // ------------------------------------------------------------------ free days from a program

    private HttpResponse<String> grant(String customer, String tier, int days, String key) {
        TestSupport.as(PROGRAM);
        return send("POST", "/v1/memberships/grants", Map.of("customerId", customer, "tierCode", tier, "days", days,
                "reason", "Referral reward: a friend's first order was delivered"), key);
    }

    @Test
    void aProgramGrantsFreeDaysIdempotentlyAndTheyExtendAMembershipThatExists() {
        String tier = tierCode("free");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("FREE_DELIVERY")));
        String customer = id("cust");

        JsonNode first = ok(grant(customer, tier, 30, "ref-" + customer), 201);
        assertThat(first.path("newMembership").asBoolean()).isTrue();
        assertThat(first.path("replay").asBoolean()).isFalse();
        Instant until = Instant.parse(first.path("benefitsUntil").asString());
        assertThat(Duration.between(Instant.now(), until).toDays()).isBetween(29L, 30L);
        JsonNode member = me(customer);
        assertThat(member.path("membership").path("source").asString()).isEqualTo("COMPLIMENTARY");
        assertThat(member.path("membership").path("freeDaysGranted").asInt()).isEqualTo(30);
        assertThat(entitlements(customer).path("member").asBoolean()).isTrue();

        // the same key again adds nothing
        JsonNode replay = ok(grant(customer, tier, 30, "ref-" + customer), 201);
        assertThat(replay.path("replay").asBoolean()).isTrue();
        assertThat(replay.path("membershipId").asString()).isEqualTo(first.path("membershipId").asString());
        assertThat(Instant.parse(replay.path("benefitsUntil").asString())).isEqualTo(until);

        // another reward adds days to the same membership
        JsonNode more = ok(grant(customer, tier, 10, "ref2-" + customer), 201);
        assertThat(more.path("newMembership").asBoolean()).isFalse();
        assertThat(Instant.parse(more.path("benefitsUntil").asString())).isEqualTo(until.plus(Duration.ofDays(10)));
        assertThat(membershipCount(customer)).isEqualTo(1);

        // the key belongs to one customer; only programs may grant; a draft tier cannot be granted
        problem(grant(id("other"), tier, 30, "ref-" + customer), 409, "payments.membership-grant-key-reused");
        TestSupport.as(TestSupport.customer(customer));
        problem(send("POST", "/v1/memberships/grants", Map.of("customerId", customer, "tierCode", tier, "days", 5,
                "reason", "A shopper cannot grant themselves days"), "x-" + customer), 403, "payments.service-only");
        assertThat(grant(id("c"), tier, 400, "big-" + customer).statusCode()).as("at most 365 days").isEqualTo(400);
    }

    @Test
    void theInProcessPortGrantsDaysToAPaidMemberAndPushesTheNextChargeBack() {
        String tier = tierCode("free");
        tier(tier, 10, 999, 9900, 0, List.of(benefit("FREE_DELIVERY")));
        String customer = member(tier);
        Instant end = Instant.parse(me(customer).path("membership").path("periodEnd").asString());

        MembershipGrants.Grant g = grants.grantDays(customer, tier, 15, "Referral reward for a friend's order",
                "port-" + customer);
        assertThat(g.newMembership()).isFalse();
        assertThat(g.benefitsUntil()).isEqualTo(end.plus(Duration.ofDays(15)));
        assertThat(grants.grantDays(customer, tier, 15, "Referral reward for a friend's order", "port-" + customer)
                .replay()).isTrue();
        assertThat(me(customer).path("membership").path("freeDaysGranted").asInt()).isEqualTo(15);
    }

    @Test
    void complimentaryDaysEndOnTheirDateOrContinueOnACardIfTheMemberJoins() {
        String tier = tierCode("free");
        tier(tier, 10, 999, 9900, 14, List.of(benefit("FREE_DELIVERY")));

        String lapsing = id("cust");
        grants.grantDays(lapsing, tier, 20, "Referral reward for a friend's order", "k1-" + lapsing);
        TestSupport.CLOCK.advance(Duration.ofDays(21));
        billing.renewDue(500);
        JsonNode over = me(lapsing);
        assertThat(over.path("membership").path("status").asString()).isEqualTo("EXPIRED");
        assertThat(entitlements(lapsing).path("member").asBoolean()).isFalse();

        String joiner = shopperWithCard();
        grants.grantDays(joiner, tier, 20, "Referral reward for a friend's order", "k2-" + joiner);
        JsonNode continued = join(joiner, tier, "MONTHLY");
        assertThat(continued.path("membership").path("source").asString()).isEqualTo("PAID");
        assertThat(continued.path("membership").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(continued.path("charges")).as("nothing is charged while the free days run").isEmpty();
        chargeWorks(joiner, RENEW, 999);
        TestSupport.CLOCK.advance(Duration.ofDays(21));
        billing.renewDue(500);
        JsonNode renewed = me(joiner);
        assertThat(renewed.path("membership").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(renewed.path("charges")).hasSize(1);
    }
}
