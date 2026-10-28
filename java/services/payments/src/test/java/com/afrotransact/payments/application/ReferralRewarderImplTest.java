package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.ReferralSide;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.port.MembershipGrants;
import com.afrotransact.payments.port.ReferralRepository;
import com.afrotransact.payments.port.ReferralSettingsRepository;
import com.afrotransact.payments.port.ReferralTrackingRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferralRewarderImplTest {

    private static final Instant DELIVERED = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant AFTER_WINDOW = DELIVERED.plusSeconds(31 * 86_400L);

    private final ReferralRepository referrals = mock(ReferralRepository.class);
    private final ReferralTrackingRepository tracking = mock(ReferralTrackingRepository.class);
    private final ReferralSettingsRepository settingsRepo = mock(ReferralSettingsRepository.class);
    private final CreditLedger ledger = mock(CreditLedger.class);
    private final MembershipGrants membership = mock(MembershipGrants.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final ReferralRewarderImpl rewarder = new ReferralRewarderImpl(referrals, tracking, settingsRepo, ledger,
            membership, events);

    private Referral waiting;
    private ReferralSettings program;

    @BeforeEach
    void setUp() {
        waiting = Referral.signedUp(UUID.randomUUID(), "ref", "friend", "BCDFGHJK", "Amara O.", List.of(), DELIVERED,
                90, DELIVERED).qualified("ORD-1", 4000, DELIVERED, 30, List.of(), DELIVERED);
        program = settings(true, ReferralReward.credit(1000), ReferralReward.credit(1000), 20, 50000);
        when(settingsRepo.get()).thenReturn(program);
        when(tracking.refundedMinor("ORD-1")).thenReturn(0L);
        when(referrals.update(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledger.add(any())).thenAnswer(inv -> {
            CreditLedger.Add a = inv.getArgument(0);
            return new CreditEntry(UUID.randomUUID(), a.customerId(), CreditKind.GRANT, a.amount().minor(), "USD",
                    a.source(), a.reason(), a.actor(), a.orderId(), a.reference(), a.idempotencyKey(),
                    a.amount().minor(), AFTER_WINDOW);
        });
    }

    private static ReferralSettings settings(boolean enabled, ReferralReward referrer, ReferralReward referred,
                                             int maxRewarded, long maxCredit) {
        return new ReferralSettings(enabled, referrer, referred, ReferralSettings.QualifyingEvent.FIRST_ORDER_DELIVERED,
                3000, 30, maxRewarded, maxCredit, 90, "system", DELIVERED, 0);
    }

    private void rewards(ReferralRewardGrant... grants) {
        when(referrals.rewards(waiting.id())).thenReturn(List.of(grants));
    }

    private ReferralRewardGrant credit(ReferralSide side, String customer, long minor) {
        return ReferralRewardGrant.pending(waiting.id(), side, customer, ReferralReward.credit(minor), "USD");
    }

    @Test
    void grantsEachSideThroughTheLedgerAsReferralCreditWithOneKeyPerSide() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000), credit(ReferralSide.REFERRED, "friend", 1000));
        Referral next = rewarder.settle(waiting, AFTER_WINDOW);
        assertThat(next.status()).isEqualTo(ReferralStatus.REWARDED);
        verify(ledger).add(argThat(a -> a.customerId().equals("ref") && a.source() == CreditSource.REFERRAL
                && a.kind() == CreditKind.GRANT && a.amount().minor() == 1000 && a.postExpense()
                && a.idempotencyKey().equals("referral:" + waiting.id() + ":referrer")));
        verify(ledger).add(argThat(a -> a.customerId().equals("friend")
                && a.idempotencyKey().equals("referral:" + waiting.id() + ":referred")));
        verify(events).publish(eq(PaymentsEvents.REFERRAL_REWARDED), anyString(),
                eq("ref"), any());
    }

    @Test
    void doesNothingInsideTheReturnWindowOrWhileTheProgramIsOff() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000));
        assertThat(rewarder.settle(waiting, DELIVERED.plusSeconds(29 * 86_400L))).isSameAs(waiting);
        when(settingsRepo.get()).thenReturn(settings(false, ReferralReward.credit(1000), ReferralReward.credit(1000),
                20, 50000));
        assertThat(rewarder.settle(waiting, AFTER_WINDOW)).isSameAs(waiting);
        verify(ledger, never()).add(any());
    }

    @Test
    void aRefundBelowTheMinimumRejectsTheReferralAndGrantsNothing() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000), credit(ReferralSide.REFERRED, "friend", 1000));
        when(tracking.refundedMinor("ORD-1")).thenReturn(2000L);
        Referral next = rewarder.settle(waiting, AFTER_WINDOW);
        assertThat(next.status()).isEqualTo(ReferralStatus.REJECTED);
        assertThat(next.decision()).isEqualTo(Referral.Decision.SYSTEM);
        verify(ledger, never()).add(any());
        verify(referrals, times(2)).updateReward(argThat(
                g -> g.status() == ReferralRewardGrant.Status.VOID));
    }

    @Test
    void aPartialRefundThatKeepsTheOrderAboveTheMinimumStillRewards() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000));
        when(tracking.refundedMinor("ORD-1")).thenReturn(1000L);
        assertThat(rewarder.settle(waiting, AFTER_WINDOW).status()).isEqualTo(ReferralStatus.REWARDED);
    }

    @Test
    void theReferrersCountCapDoesNotStopTheFriendsReward() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000), credit(ReferralSide.REFERRED, "friend", 1000));
        when(settingsRepo.get()).thenReturn(settings(true, ReferralReward.credit(1000), ReferralReward.credit(1000),
                2, 50000));
        when(referrals.rewardedCount("ref")).thenReturn(2);
        Referral next = rewarder.settle(waiting, AFTER_WINDOW);
        assertThat(next.status()).isEqualTo(ReferralStatus.REWARDED);
        verify(ledger, never()).add(argThat(a -> a.customerId().equals("ref")));
        verify(ledger).add(argThat(a -> a.customerId().equals("friend")));
        verify(referrals).updateReward(argThat(g -> g.side() == ReferralSide.REFERRER
                && g.status() == ReferralRewardGrant.Status.CAPPED));
    }

    @Test
    void theCreditCapAppliesToWhatACustomerEarnedInAll() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000), credit(ReferralSide.REFERRED, "friend", 1000));
        when(settingsRepo.get()).thenReturn(settings(true, ReferralReward.credit(1000), ReferralReward.credit(1000),
                20, 1500));
        when(referrals.creditGrantedTo("ref")).thenReturn(1000L);
        rewarder.settle(waiting, AFTER_WINDOW);
        verify(ledger, never()).add(argThat(a -> a.customerId().equals("ref")));
        verify(ledger).add(argThat(a -> a.customerId().equals("friend")));
    }

    @Test
    void bothSidesCappedEndsTheReferral() {
        rewards(credit(ReferralSide.REFERRER, "ref", 1000), credit(ReferralSide.REFERRED, "friend", 1000));
        when(settingsRepo.get()).thenReturn(settings(true, ReferralReward.credit(1000), ReferralReward.credit(1000),
                20, 500));
        Referral next = rewarder.settle(waiting, AFTER_WINDOW);
        assertThat(next.status()).isEqualTo(ReferralStatus.REJECTED);
        verify(ledger, never()).add(any());
    }

    @Test
    void membershipDaysGoThroughThePortAndStayDeferredUntilItIsAvailable() {
        ReferralRewardGrant days = ReferralRewardGrant.pending(waiting.id(), ReferralSide.REFERRED, "friend",
                ReferralReward.days(14), "USD");
        rewards(credit(ReferralSide.REFERRER, "ref", 1000), days);
        when(membership.grant(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(MembershipGrants.Outcome.UNAVAILABLE);
        Referral partial = rewarder.settle(waiting, AFTER_WINDOW);
        assertThat(partial.status()).isEqualTo(ReferralStatus.PARTIALLY_REWARDED);
        verify(referrals).updateReward(argThat(g -> g.side() == ReferralSide.REFERRED
                && g.status() == ReferralRewardGrant.Status.DEFERRED));

        // the membership program lands: the next run grants the days (the credit side is already granted)
        ReferralRewardGrant granted = credit(ReferralSide.REFERRER, "ref", 1000).granted(UUID.randomUUID(),
                AFTER_WINDOW);
        rewards(granted, days.with(ReferralRewardGrant.Status.DEFERRED));
        when(membership.grant("friend", 14, "Referral welcome reward: your first order was delivered",
                "referral:" + waiting.id() + ":referred")).thenReturn(MembershipGrants.Outcome.GRANTED);
        Referral done = rewarder.settle(partial, AFTER_WINDOW.plusSeconds(3600));
        assertThat(done.status()).isEqualTo(ReferralStatus.REWARDED);
        verify(ledger, times(1)).add(any());
    }
}
