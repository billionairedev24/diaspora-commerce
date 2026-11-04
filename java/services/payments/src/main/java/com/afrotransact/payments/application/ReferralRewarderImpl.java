package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.ReferralSide;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.domain.RewardType;
import com.afrotransact.payments.port.MembershipGrants;
import com.afrotransact.payments.port.ReferralRepository;
import com.afrotransact.payments.port.ReferralSettingsRepository;
import com.afrotransact.payments.port.ReferralTrackingRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

@Component
class ReferralRewarderImpl implements ReferralRewarder {

    static final String ACTOR = "referral-program";

    private final ReferralRepository referrals;
    private final ReferralTrackingRepository tracking;
    private final ReferralSettingsRepository settings;
    private final CreditLedger ledger;
    private final MembershipGrants membership;
    private final MembershipBenefitsService benefits;
    private final DomainEventPublisher events;

    ReferralRewarderImpl(ReferralRepository referrals, ReferralTrackingRepository tracking,
                         ReferralSettingsRepository settings, CreditLedger ledger, MembershipGrants membership,
                         MembershipBenefitsService benefits, DomainEventPublisher events) {
        this.referrals = referrals;
        this.tracking = tracking;
        this.settings = settings;
        this.ledger = ledger;
        this.membership = membership;
        this.benefits = benefits;
        this.events = events;
    }

    @Override
    public Referral settle(Referral r, Instant now) {
        ReferralSettings program = settings.get();
        if (!program.enabled() || !r.due(now)) {
            return r;
        }
        if (r.status() == ReferralStatus.WAITING) {
            long refunded = tracking.refundedMinor(r.qualifyingOrderId());
            if (!program.qualifies(r.qualifyingMinor() - refunded)) {
                return rejectBySystem(r, "The qualifying order was refunded below the minimum order value", now);
            }
        }
        boolean anyGranted = false;
        boolean anyOpen = false;
        boolean anyCapped = false;
        boolean anyReward = false;
        for (ReferralRewardGrant g : referrals.rewards(r.id())) {
            anyReward = true;
            ReferralRewardGrant result = g.open() ? tryGrant(r, g, program, now) : g;
            anyGranted |= result.status() == ReferralRewardGrant.Status.GRANTED;
            anyOpen |= result.open();
            anyCapped |= result.status() == ReferralRewardGrant.Status.CAPPED;
        }
        if (anyOpen) {
            return referrals.update(r.settled(anyGranted ? ReferralStatus.PARTIALLY_REWARDED : r.status(), now));
        }
        if (anyReward && !anyGranted && anyCapped) {
            return reject(r, "Both rewards are over the per-customer caps", now);
        }
        return referrals.update(r.settled(ReferralStatus.REWARDED, now));
    }

    /**
     * A member's reward is bigger by the boost of the tier (the BOOSTED_REWARDS benefit, S323): credit and days are both
     * raised, within the sanity bounds of a reward. The grant keeps the terms it was really given.
     */
    private ReferralRewardGrant boosted(ReferralRewardGrant g) {
        int bps = benefits.rewardBoostBps(g.customerId());
        ReferralReward base = g.reward();
        if (bps <= 0 || base.isEmpty()) {
            return g;
        }
        if (base.type() == RewardType.CREDIT) {
            long credit = base.creditMinor() + Math.multiplyExact(base.creditMinor(), (long) bps) / 10_000L;
            return g.withReward(ReferralReward.credit(Math.min(credit, ReferralReward.MAX_CREDIT_MINOR)));
        }
        long days = base.membershipDays() + Math.multiplyExact((long) base.membershipDays(), (long) bps) / 10_000L;
        return g.withReward(ReferralReward.days((int) Math.min(days, ReferralReward.MAX_DAYS)));
    }

    private ReferralRewardGrant tryGrant(Referral r, ReferralRewardGrant offered, ReferralSettings program,
                                         Instant now) {
        ReferralRewardGrant g = boosted(offered);
        if (capped(g, program)) {
            ReferralRewardGrant capped = offered.with(ReferralRewardGrant.Status.CAPPED);
            referrals.updateReward(capped);
            return capped;
        }
        ReferralRewardGrant result;
        if (g.reward().type() == RewardType.CREDIT) {
            CreditEntry entry = ledger.add(new CreditLedger.Add(g.customerId(), CreditKind.GRANT,
                    Money.of(g.reward().creditMinor(), g.currency()), CreditSource.REFERRAL, reason(g), ACTOR, null,
                    r.id().toString(), g.idempotencyKey(), true));
            result = g.granted(entry.id(), now);
        } else {
            MembershipGrants.Outcome outcome = membership.grant(g.customerId(), g.reward().membershipDays(),
                    reason(g), g.idempotencyKey());
            result = outcome == MembershipGrants.Outcome.GRANTED ? g.granted(null, now)
                    : offered.with(ReferralRewardGrant.Status.DEFERRED);
        }
        referrals.updateReward(result);
        if (result.status() == ReferralRewardGrant.Status.GRANTED) {
            events.publish(PaymentsEvents.REFERRAL_REWARDED, PaymentsEvents.AGGREGATE_PAYMENT, g.customerId(),
                    new PaymentsEventPayloads.ReferralRewardedPayload(r.id().toString(), g.customerId(),
                            g.side().name(), g.reward().type().name(), g.reward().creditMinor(), g.currency(),
                            g.reward().membershipDays(), g.side() == ReferralSide.REFERRER ? r.referredName() : null));
        }
        return result;
    }

    /** A per-customer cap: the referrer's count of rewarded referrals, and the credit a customer can earn in all. */
    private boolean capped(ReferralRewardGrant g, ReferralSettings program) {
        if (g.side() == ReferralSide.REFERRER && program.maxRewardedPerReferrer() > 0
                && referrals.rewardedCount(g.customerId()) >= program.maxRewardedPerReferrer()) {
            return true;
        }
        return g.reward().type() == RewardType.CREDIT && program.maxCreditPerCustomerMinor() > 0
                && referrals.creditGrantedTo(g.customerId()) + g.reward().creditMinor()
                > program.maxCreditPerCustomerMinor();
    }

    private static String reason(ReferralRewardGrant g) {
        return g.side() == ReferralSide.REFERRER ? "Referral reward: a friend you invited had a first order delivered"
                : "Referral welcome reward: your first order was delivered";
    }

    @Override
    public Referral rejectBySystem(Referral r, String reason, Instant now) {
        return reject(r, reason, now);
    }

    private Referral reject(Referral r, String reason, Instant now) {
        voidOpen(r);
        Referral next = referrals.update(r.rejectedBySystem(reason, now));
        decided(next);
        return next;
    }

    @Override
    public Referral rejectByStaff(Referral r, String staff, String reason, Instant now) {
        voidOpen(r);
        Referral next = referrals.update(r.rejectedByStaff(staff, reason, now));
        decided(next);
        return next;
    }

    private void voidOpen(Referral r) {
        List<ReferralRewardGrant> rewards = referrals.rewards(r.id());
        rewards.stream().filter(ReferralRewardGrant::open)
                .forEach(g -> referrals.updateReward(g.with(ReferralRewardGrant.Status.VOID)));
    }

    private void decided(Referral r) {
        events.publish(PaymentsEvents.REFERRAL_DECIDED, PaymentsEvents.AGGREGATE_PAYMENT, r.id().toString(),
                new PaymentsEventPayloads.ReferralDecidedPayload(r.id().toString(), r.referrerId(), r.referredId(),
                        r.decision().name(), r.decisionReason(), r.decidedBy()));
    }
}
