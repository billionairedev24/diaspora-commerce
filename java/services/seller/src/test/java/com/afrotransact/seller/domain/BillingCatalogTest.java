package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Plan versioning, Stripe drift states, trials, free days and coupons (S292–S295). */
class BillingCatalogTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final Plan GROWTH = Plan.standard("seller_growth", OrgKind.SELLER, "Seller Growth", null, 3900, 30,
            List.of(), 20);

    @Test
    void aVersionChangesOnlyWhenPricesOrTrialChange() {
        PlanVersion v1 = new PlanVersion("seller_growth", 1, PlanVersionStatus.PUBLISHED,
                PlanVersion.prices(3900, 11115, 39000), 30, null, "system", NOW, NOW);
        assertThat(v1.differsFrom(PlanVersion.prices(3900, 11115, 39000), 30)).isFalse();
        assertThat(v1.differsFrom(PlanVersion.prices(4900, 11115, 39000), 30)).as("price").isTrue();
        assertThat(v1.differsFrom(PlanVersion.prices(3900, 11115, 39000), 14)).as("trial").isTrue();
        assertThat(Plan.standardPrice(4900, Cadence.QUARTERLY)).isEqualTo(13965);
        assertThat(Plan.standardPrice(4900, Cadence.ANNUAL)).isEqualTo(49000);
    }

    @Test
    void planEditsAreValidatedTogether() {
        assertThatThrownBy(() -> PlanCatalogRules.validate(new PlanInput("Bad Code", null, "", null, List.of(),
                Map.of(), null, null), true))
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKeys("code", "kind", "name"));
        assertThatThrownBy(() -> PlanCatalogRules.validate(new PlanPricesInput(2500L, 0L, 25000L, 30, null)))
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKey("monthlyCents"));
        assertThatThrownBy(() -> PlanCatalogRules.validate(new PlanPricesInput(2500L, 9000L, 25000L, 30, null)))
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKey("quarterlyCents"));
        assertThatThrownBy(() -> PlanCatalogRules.validate(new PlanPricesInput(0L, 0L, 0L, 14, null)))
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKey("trialDays"));
        PlanCatalogRules.validate(new PlanPricesInput(2500L, 7125L, 25000L, 30, "launch"));
    }

    @Test
    void anOverrideWinsElseTheLongestOfPlanAndMatchingCampaigns() {
        TrialCampaign october = campaign(null, 60, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"), List.of());
        TrialCampaign code = campaign("GROW90", 90, null, null, List.of("seller_growth"));
        assertThat(october.appliesTo(NOW, OrgKind.SELLER, "seller_growth", null)).isTrue();
        assertThat(october.appliesTo(Instant.parse("2026-11-01T00:00:00Z"), OrgKind.SELLER, "seller_growth", null))
                .as("window end is exclusive").isFalse();
        assertThat(code.appliesTo(NOW, OrgKind.SELLER, "seller_growth", null)).as("needs its code").isFalse();
        assertThat(code.appliesTo(NOW, OrgKind.SELLER, "seller_growth", " grow90 ")).isTrue();
        assertThat(code.appliesTo(NOW, OrgKind.SELLER, "seller_pro", "GROW90")).as("eligible plans").isFalse();

        assertThat(TrialDecision.decide(GROWTH, null, List.of())).isEqualTo(new TrialDecision(30, TrialSource.PLAN,
                null));
        assertThat(TrialDecision.decide(GROWTH, null, List.of(october, code)))
                .isEqualTo(new TrialDecision(90, TrialSource.CAMPAIGN, code.id()));
        assertThat(TrialDecision.decide(GROWTH, 7, List.of(october))).as("staff override, even shorter")
                .isEqualTo(new TrialDecision(7, TrialSource.OVERRIDE, null));
        Plan free = Plan.standard("seller_essentials", OrgKind.SELLER, "Essentials", null, 0, 0, List.of(), 10);
        assertThat(TrialDecision.decide(free, 30, List.of(october)).days()).isZero();
        TrialCampaign full = new TrialCampaign(UUID.randomUUID(), "Full", null, 45, null, null, null, List.of(), 1, 1,
                CampaignStatus.ACTIVE, "s", NOW, "s", NOW, 0);
        assertThat(full.appliesTo(NOW, OrgKind.SELLER, "seller_growth", null)).as("max redemptions").isFalse();
    }

    @Test
    void campaignsNeedACodeOrAWindow() {
        assertThatThrownBy(() -> new CampaignInput("X", null, 30, null, null, null, null, null, null).validate())
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKey("signupFrom"));
        assertThatThrownBy(() -> new CampaignInput("X", "no spaces!", 0, null, null, null, null, null, null)
                .validate()).isInstanceOfSatisfying(SellerRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKeys("promoCode", "trialDays"));
        new CampaignInput("October", "oct-60", 60, null, null, OrgKind.SELLER, List.of(), 100, null).validate();
    }

    @Test
    void freeDaysMoveTrialEndAndCouponsLastWholeCadences() {
        Instant trialEnd = Instant.parse("2026-11-01T00:00:00Z");
        Instant periodEnd = Instant.parse("2026-10-30T00:00:00Z");
        assertThat(FreeDays.newTrialEnd(SubscriptionStatus.TRIALING, trialEnd, periodEnd, NOW, 30))
                .as("a trial is extended").isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(FreeDays.newTrialEnd(SubscriptionStatus.ACTIVE, null, periodEnd, NOW, 30))
                .as("an active subscription keeps its paid period, then gets the free days")
                .isEqualTo(Instant.parse("2026-11-29T00:00:00Z"));
        assertThat(FreeDays.newTrialEnd(SubscriptionStatus.PAST_DUE, null, null, NOW, 10))
                .isEqualTo(NOW.plusSeconds(10 * 86400L));
        assertThat(FreeDays.couponMonths(Cadence.MONTHLY, 3)).isEqualTo(3);
        assertThat(FreeDays.couponMonths(Cadence.QUARTERLY, 3)).isEqualTo(9);
        assertThat(FreeDays.couponMonths(Cadence.ANNUAL, 1)).isEqualTo(12);
        assertThat(RewardLabels.reward(RewardType.FREE_DAYS, 30, null, null, null)).isEqualTo("1 month free");
        assertThat(RewardLabels.reward(RewardType.CREDIT, null, 2500L, null, null)).isEqualTo("$25 credit");
        assertThat(RewardLabels.reward(RewardType.PERCENT_OFF, null, null, new BigDecimal("20.00"), 3))
                .isEqualTo("20% off your next 3 invoices");
    }

    private static TrialCampaign campaign(String code, int days, Instant from, Instant to, List<String> plans) {
        return new TrialCampaign(UUID.randomUUID(), "c", code, days, from, to, OrgKind.SELLER, plans, null, 0,
                CampaignStatus.ACTIVE, "staff", NOW, "staff", NOW, 0);
    }
}
