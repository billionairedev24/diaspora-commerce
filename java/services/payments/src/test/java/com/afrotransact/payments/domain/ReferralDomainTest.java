package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReferralDomainTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static ReferralSettings settings(long min, int window, int maxRewarded, long maxCredit, int validity) {
        return new ReferralSettings(true, ReferralReward.credit(1000), ReferralReward.credit(1000),
                ReferralSettings.QualifyingEvent.FIRST_ORDER_DELIVERED, min, window, maxRewarded, maxCredit,
                validity, "system", T0, 0);
    }

    private static Referral pending() {
        return Referral.signedUp(UUID.randomUUID(), "ref", "friend", "BCDFGHJK", "Amara O.", List.of(), T0, 90, T0);
    }

    // ------------------------------------------------------------------ codes

    @Test
    void codesAreRandomUnambiguousAndCannotSpellWords() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = ReferralCodes.generate(random);
            assertThat(code).matches("[BCDFGHJKMNPQRSTVWXZ2-9]{8}").doesNotContainPattern("[AEIOUY01ILl]");
            seen.add(code);
        }
        assertThat(seen).as("no collisions in 2000 draws").hasSize(2000);
    }

    @Test
    void codesAreNormalisedFromAnyTypingAndRejectOtherInput() {
        assertThat(ReferralCodes.normalise(" kdm7-xr3q ")).isEqualTo("KDM7XR3Q");
        assertThat(ReferralCodes.normalise("KDM7 XR3Q")).isEqualTo("KDM7XR3Q");
        assertThat(ReferralCodes.normalise("KDM7XR3")).as("too short").isNull();
        assertThat(ReferralCodes.normalise("KDM7XR3QQ")).as("too long").isNull();
        assertThat(ReferralCodes.normalise("KDM7XR3A")).as("vowel").isNull();
        assertThat(ReferralCodes.normalise("KDM7XR30")).as("zero").isNull();
        assertThat(ReferralCodes.normalise("<script>")).isNull();
        assertThat(ReferralCodes.normalise(null)).isNull();
        assertThat(ReferralCodes.display("KDM7XR3Q")).isEqualTo("KDM7-XR3Q");
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsValidateEverySetting() {
        assertThat(settings(3000, 30, 20, 50000, 90).qualifies(3000)).isTrue();
        assertThat(settings(3000, 30, 20, 50000, 90).qualifies(2999)).isFalse();
        assertThatThrownBy(() -> settings(-1, 30, 20, 50000, 90)).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> settings(3000, 181, 20, 50000, 90)).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> settings(3000, 30, -1, 50000, 90)).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> settings(3000, 30, 20, -5, 90)).isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> settings(3000, 30, 20, 50000, 0)).isInstanceOf(PaymentsRuleViolation.class);
        assertThat(settings(0, 0, 0, 0, 1).returnWindowDays()).as("zero caps mean no limit").isZero();
    }

    @Test
    void eachSideHasItsOwnRewardOfCreditOrMembershipDays() {
        ReferralSettings s = new ReferralSettings(true, ReferralReward.credit(1500), ReferralReward.days(14),
                ReferralSettings.QualifyingEvent.FIRST_ORDER_DELIVERED, 3000, 30, 20, 50000, 90, "fin", T0, 3);
        assertThat(s.rewardOf(ReferralSide.REFERRER).creditMinor()).isEqualTo(1500);
        assertThat(s.rewardOf(ReferralSide.REFERRED).type()).isEqualTo(RewardType.MEMBERSHIP_DAYS);
        assertThat(s.rewardOf(ReferralSide.REFERRED).membershipDays()).isEqualTo(14);
        assertThatThrownBy(() -> new ReferralReward(RewardType.CREDIT, 100, 5)).as("credit with days")
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> new ReferralReward(RewardType.MEMBERSHIP_DAYS, 100, 5)).as("days with credit")
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> ReferralReward.credit(ReferralReward.MAX_CREDIT_MINOR + 1))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThat(ReferralReward.credit(0).isEmpty()).isTrue();
        assertThat(ReferralReward.days(1).isEmpty()).isFalse();
    }

    // ------------------------------------------------------------------ the referral lifecycle

    @Test
    void aReferralCannotBeItsOwnReferrer() {
        assertThatThrownBy(() -> Referral.signedUp(UUID.randomUUID(), "same", "same", "BCDFGHJK", "A", List.of(), T0,
                90, T0)).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void signUpOpensAValidityWindow() {
        Referral r = pending();
        assertThat(r.status()).isEqualTo(ReferralStatus.PENDING);
        assertThat(r.expiresAt()).isEqualTo(T0.plusSeconds(90 * 86_400L));
        assertThat(r.due(T0.plusSeconds(365 * 86_400L))).isFalse();
    }

    @Test
    void qualifyingStartsTheReturnWindowAndFlagsHoldItForStaff() {
        Instant delivered = T0.plusSeconds(5 * 86_400L);
        Referral clean = pending().qualified("ORD-1", 4000, delivered, 30, List.of(), T0);
        assertThat(clean.status()).isEqualTo(ReferralStatus.WAITING);
        assertThat(clean.eligibleAt()).isEqualTo(delivered.plusSeconds(30 * 86_400L));
        assertThat(clean.due(delivered.plusSeconds(29 * 86_400L))).isFalse();
        assertThat(clean.due(delivered.plusSeconds(30 * 86_400L))).isTrue();

        Referral flagged = pending().qualified("ORD-1", 4000, delivered, 30, List.of(ReferralFlag.SAME_DEVICE), T0);
        assertThat(flagged.status()).isEqualTo(ReferralStatus.IN_REVIEW);
        assertThat(flagged.due(delivered.plusSeconds(400 * 86_400L))).as("never due while in review").isFalse();
        assertThat(flagged.flags()).containsExactly(ReferralFlag.SAME_DEVICE);
    }

    @Test
    void staffApproveBackToWaitingOrRejectAndOnlyFromTheRightStates() {
        Instant delivered = T0.plusSeconds(86_400L);
        Referral flagged = pending().qualified("ORD-1", 4000, delivered, 30, List.of(ReferralFlag.SAME_ADDRESS), T0);
        Referral approved = flagged.approved("fin-1", "Housemates, checked by phone", T0);
        assertThat(approved.status()).isEqualTo(ReferralStatus.WAITING);
        assertThat(approved.decision()).isEqualTo(Referral.Decision.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("fin-1");
        assertThatThrownBy(() -> approved.approved("fin-1", "again", T0)).isInstanceOf(PaymentsStateConflict.class);

        Referral rejected = flagged.rejectedByStaff("fin-2", "Same household, second account", T0);
        assertThat(rejected.status()).isEqualTo(ReferralStatus.REJECTED);
        assertThat(rejected.status().isFinal()).isTrue();
        assertThatThrownBy(() -> pending().rejectedByStaff("fin-2", "no order yet", T0))
                .isInstanceOf(PaymentsStateConflict.class);
        assertThatThrownBy(() -> pending().approved("fin-2", "not flagged", T0))
                .isInstanceOf(PaymentsStateConflict.class);
    }

    @Test
    void onlyAPendingReferralExpiresAndFlagsAreMergedWithoutDuplicates() {
        assertThat(pending().expired(T0).status()).isEqualTo(ReferralStatus.EXPIRED);
        Referral waiting = pending().qualified("ORD-1", 4000, T0, 30, List.of(), T0);
        assertThatThrownBy(() -> waiting.expired(T0)).isInstanceOf(PaymentsStateConflict.class);
        Referral twice = pending().withFlags(List.of(ReferralFlag.SAME_DEVICE), T0)
                .withFlags(List.of(ReferralFlag.SAME_DEVICE, ReferralFlag.SAME_NETWORK), T0);
        assertThat(twice.flags()).containsExactly(ReferralFlag.SAME_DEVICE, ReferralFlag.SAME_NETWORK);
    }

    @Test
    void aRewardGrantIsOpenUntilGrantedCappedOrVoided() {
        UUID id = UUID.randomUUID();
        ReferralRewardGrant g = ReferralRewardGrant.pending(id, ReferralSide.REFERRER, "ref",
                ReferralReward.credit(1000), "USD");
        assertThat(g.idempotencyKey()).isEqualTo("referral:" + id + ":referrer");
        assertThat(ReferralRewardGrant.pending(id, ReferralSide.REFERRED, "friend", ReferralReward.credit(1000), "USD")
                .idempotencyKey()).isEqualTo("referral:" + id + ":referred");
        assertThat(g.open()).isTrue();
        assertThat(g.with(ReferralRewardGrant.Status.DEFERRED).open()).isTrue();
        assertThat(g.granted(UUID.randomUUID(), T0).open()).isFalse();
        assertThat(g.with(ReferralRewardGrant.Status.CAPPED).open()).isFalse();
        assertThat(g.with(ReferralRewardGrant.Status.VOID).open()).isFalse();
    }
}
