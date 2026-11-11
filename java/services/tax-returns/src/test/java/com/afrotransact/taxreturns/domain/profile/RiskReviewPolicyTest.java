package com.afrotransact.taxreturns.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The risk-based review rule (listing-rules.md D7): auto-approve only when the category is ACTIVE, the rules resolve,
 * no required evidence is missing and the category is not always reviewed. Everything else goes to a person.
 */
class RiskReviewPolicyTest {

    private static Map<String, TaxProfile> profiles;

    @BeforeAll
    static void load() {
        profiles = new LinkedHashMap<>();
        TaxMapFixture.profiles(TaxMapFixture.read("category_tax_map.json")).forEach(p -> profiles.put(p.categoryCode(), p));
    }

    private static Resolution resolved() {
        return new Resolution(ResolveStatus.RESOLVED, Outcome.TAXABLE, "AT.TEST.CODE", Map.of(), "S001", "8.25%",
                List.of(), "tx-test.1", "Matched", 0);
    }

    private static Resolution with(ResolveStatus status) {
        return new Resolution(status, null, null, Map.of(), null, null, List.of(), "tx-test.1", "Not clean", 0);
    }

    private static RiskReviewPolicy policy() {
        return new RiskReviewPolicy(ReviewSettings.defaults());
    }

    private static TaxProfile profile(String code, ProfileStatus status) {
        return profiles.get(code).withStatus(status);
    }

    @Test
    void anActiveResolvedCategoryOutsideTheListIsApprovedBySystem() {
        RiskReviewDecision d = policy().decide(profile("haircare", ProfileStatus.ACTIVE), resolved(), Map.of());

        assertThat(d.autoApprove()).isTrue();
        assertThat(d.rule()).contains("ACTIVE").contains("resolved cleanly").contains("row S001")
                .contains("always-review list");
    }

    @Test
    void everyCategoryStatusOtherThanActiveGoesToAPerson() {
        for (ProfileStatus status : ProfileStatus.values()) {
            RiskReviewDecision d = policy().decide(profile("haircare", status), resolved(), Map.of());

            assertThat(d.autoApprove()).as("status %s", status).isEqualTo(status == ProfileStatus.ACTIVE);
            if (status != ProfileStatus.ACTIVE) {
                assertThat(d.rule()).contains(status.name());
            }
        }
    }

    @Test
    void theStatusRuleHoldsEvenWhenTheCategoryIsRemovedFromTheAlwaysReviewList() {
        RiskReviewPolicy none = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 5));

        for (ProfileStatus status : List.of(ProfileStatus.NEEDS_APPROVAL, ProfileStatus.REFINE_CODE,
                ProfileStatus.CONFLICT_OPEN, ProfileStatus.BLOCKED_BUNDLE)) {
            assertThat(none.decide(profile("gift-cards", status), resolved(), Map.of()).autoApprove())
                    .as("status %s", status).isFalse();
        }
    }

    @Test
    void everyResolveStatusOtherThanResolvedGoesToAPerson() {
        for (ResolveStatus status : ResolveStatus.values()) {
            RiskReviewDecision d = policy().decide(profile("haircare", ProfileStatus.ACTIVE), with(status), Map.of());

            assertThat(d.autoApprove()).as("resolve status %s", status).isEqualTo(status == ResolveStatus.RESOLVED);
        }
        assertThat(policy().decide(profile("haircare", ProfileStatus.ACTIVE), null, Map.of()).autoApprove()).isFalse();
    }

    @Test
    void theDefaultAlwaysReviewListIsTheSixRegulatedCategoriesAndTheyAreReviewedEvenWhenActiveAndResolved() {
        assertThat(ReviewSettings.defaults().alwaysReview()).containsExactlyInAnyOrder("dietary-supplements",
                "otc-medicines", "medicated-skincare", "medical-devices", "baby-food-formula", "gift-cards");
        for (String code : ReviewSettings.DEFAULT_ALWAYS_REVIEW) {
            RiskReviewDecision d = policy().decide(profile(code, ProfileStatus.ACTIVE), resolved(), allEvidence(code));

            assertThat(d.autoApprove()).as(code).isFalse();
            assertThat(d.rule()).contains("always-review list");
        }
    }

    @Test
    void theAlwaysReviewListIsConfigurable() {
        RiskReviewPolicy extra = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of("haircare"), 5));
        RiskReviewPolicy emptied = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 5));

        assertThat(extra.decide(profile("haircare", ProfileStatus.ACTIVE), resolved(), Map.of()).autoApprove()).isFalse();
        assertThat(emptied.decide(profile("baby-food-formula", ProfileStatus.ACTIVE), resolved(), Map.of()).autoApprove())
                .isTrue();
    }

    @Test
    void requiredEvidenceThatIsMissingGoesToAPersonAndPresentEvidenceDoesNot() {
        TaxProfile magazines = profile("magazines", ProfileStatus.ACTIVE);
        Map<String, Object> noProof = Map.of("publication_type", "QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER");
        Map<String, Object> withProof = Map.of("publication_type", "QUALIFYING_EXEMPT_NEWSPAPER_OR_OTHER",
                "publication_evidence", "asset_1");
        Map<String, Object> ordinary = Map.of("publication_type", "ORDINARY_MAGAZINE_PERIODICAL");

        RiskReviewDecision missing = policy().decide(magazines, resolved(), noProof);

        assertThat(missing.autoApprove()).isFalse();
        assertThat(missing.rule()).contains("Evidence");
        assertThat(policy().decide(magazines, resolved(), withProof).autoApprove()).isTrue();
        assertThat(policy().decide(magazines, resolved(), ordinary).autoApprove()).as("evidence not required here").isTrue();
    }

    @Test
    void samplingIsDeterministicSoARetryNeverSamplesTwiceOrDifferently() {
        RiskReviewPolicy a = policy();
        RiskReviewPolicy b = new RiskReviewPolicy(ReviewSettings.defaults());

        for (int i = 0; i < 500; i++) {
            String atin = "AT" + String.format("%08d", i);
            assertThat(a.sampledForAudit(atin)).isEqualTo(a.sampledForAudit(atin)).isEqualTo(b.sampledForAudit(atin));
        }
    }

    @Test
    void theSampleIsAboutTheConfiguredPercentage() {
        long sampled = IntStream.range(0, 20_000).filter(i -> policy().sampledForAudit("AT" + String.format("%08d", i)))
                .count();

        assertThat(sampled).isBetween(800L, 1_200L);   // 5% of 20,000 is 1,000
    }

    @Test
    void aLargerPercentageOnlyAddsItemsAndTheEdgesAreExact() {
        RiskReviewPolicy five = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 5));
        RiskReviewPolicy twenty = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 20));
        RiskReviewPolicy none = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 0));
        RiskReviewPolicy all = new RiskReviewPolicy(new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 100));

        for (int i = 0; i < 2_000; i++) {
            String atin = "AT" + String.format("%08d", i);
            if (five.sampledForAudit(atin)) {
                assertThat(twenty.sampledForAudit(atin)).isTrue();
            }
            assertThat(none.sampledForAudit(atin)).isFalse();
            assertThat(all.sampledForAudit(atin)).isTrue();
        }
    }

    @Test
    void theSettingsDefaultToTheSafeModeAndRejectAnImpossiblePercentage() {
        ReviewSettings defaults = ReviewSettings.defaults();

        assertThat(defaults.mode()).isEqualTo(ReviewMode.ALL);
        assertThat(defaults.riskBased()).isFalse();
        assertThat(defaults.auditPercent()).isEqualTo(5);
        assertThat(new ReviewSettings(null, null, 5).mode()).isEqualTo(ReviewMode.ALL);
        assertThatThrownBy(() -> new ReviewSettings(ReviewMode.RISK_BASED, List.of(), 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewSettings(ReviewMode.RISK_BASED, List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Facts that satisfy every evidence the category could require, so only the always-review rule can send it. */
    private static Map<String, Object> allEvidence(String code) {
        Map<String, Object> facts = new LinkedHashMap<>();
        for (ProfileFact f : profiles.get(code).facts()) {
            if (f.definition().evidence()) {
                facts.put(f.id(), "asset_1");
            }
        }
        return facts;
    }
}
