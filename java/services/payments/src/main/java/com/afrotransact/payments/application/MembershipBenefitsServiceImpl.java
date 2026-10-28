package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.FeeWaivers;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.TierBenefit;
import com.afrotransact.payments.port.MembershipActivityRepository;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import com.afrotransact.payments.port.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class MembershipBenefitsServiceImpl implements MembershipBenefitsService {

    private static final Logger LOG = LoggerFactory.getLogger(MembershipBenefitsServiceImpl.class);

    private final MembershipRepository memberships;
    private final MembershipTierRepository tiers;
    private final MembershipActivityRepository activity;
    private final PaymentRepository payments;
    private final MembershipCredits credits;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    MembershipBenefitsServiceImpl(MembershipRepository memberships, MembershipTierRepository tiers,
                                  MembershipActivityRepository activity, PaymentRepository payments,
                                  MembershipCredits credits, MoneyTransactions tx, PaymentsAccess access,
                                  Clock clock) {
        this.memberships = memberships;
        this.tiers = tiers;
        this.activity = activity;
        this.payments = payments;
        this.credits = credits;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public Entitlements entitlementsOf(String customerId) {
        access.creditReader();
        return entitlements(customerId);
    }

    @Override
    public Entitlements myEntitlements() {
        return entitlements(access.customer().subject());
    }

    private Entitlements entitlements(String customerId) {
        Optional<Membership> found = memberships.liveOf(customerId);
        if (found.isEmpty() || !found.get().benefitsActive()) {
            return none(customerId);
        }
        Membership m = found.get();
        MembershipTier tier = tiers.find(m.tierCode()).orElse(null);
        if (tier == null) {
            return none(customerId);
        }
        List<TierBenefit> active = tier.benefits().stream()
                .filter(b -> !b.code().grantsCredit() || m.creditBenefitsActive()).toList();
        int early = active.stream().filter(b -> b.code() == BenefitCode.EARLY_ACCESS).mapToInt(TierBenefit::hours)
                .max().orElse(0);
        int boost = active.stream().filter(b -> b.code() == BenefitCode.BOOSTED_REWARDS)
                .mapToInt(TierBenefit::percentBps).max().orElse(0);
        return new Entitlements(true, customerId, tier.code(), tier.name(), m.status(), active,
                active.stream().anyMatch(b -> b.code() == BenefitCode.PRIORITY_SUPPORT),
                active.stream().anyMatch(b -> b.code() == BenefitCode.FREE_RETURNS),
                active.stream().anyMatch(b -> b.code() == BenefitCode.MEMBER_PRICES), early, boost);
    }

    private static Entitlements none(String customerId) {
        return new Entitlements(false, customerId, null, null, null, List.of(), false, false, false, 0, 0);
    }

    // ------------------------------------------------------------------ fee waivers

    @Override
    public WaiverQuote quote(String customerId, long itemsMinor, String currency, List<FeeWaivers.Fee> fees) {
        access.creditReader();
        return waivers(customerId, itemsMinor, fees, false);
    }

    @Override
    public WaiverQuote quoteMine(long itemsMinor, String currency, List<FeeWaivers.Fee> fees) {
        return waivers(access.customer().subject(), itemsMinor, fees, true);
    }

    private WaiverQuote waivers(String customerId, long itemsMinor, List<FeeWaivers.Fee> fees, boolean withUpsell) {
        Optional<Membership> found = memberships.liveOf(customerId).filter(Membership::benefitsActive);
        if (found.isPresent()) {
            Membership m = found.get();
            Optional<MembershipTier> tier = tiers.find(m.tierCode());
            if (tier.isPresent()) {
                List<FeeWaivers.Waiver> waived = FeeWaivers.resolve(tier.get(), itemsMinor, fees);
                return new WaiverQuote(true, tier.get().code(), tier.get().name(), waived, FeeWaivers.total(waived),
                        null);
            }
        }
        return new WaiverQuote(false, null, null, List.of(), 0, withUpsell ? upsell(itemsMinor, fees) : null);
    }

    /** The cheapest published tier that would waive something on this order, and what it would save. */
    private Upsell upsell(long itemsMinor, List<FeeWaivers.Fee> fees) {
        Upsell best = null;
        for (MembershipTier tier : tiers.published()) {
            long saving = FeeWaivers.total(FeeWaivers.resolve(tier, itemsMinor, fees));
            if (saving > 0 && (best == null || tier.monthlyPriceMinor() < best.monthlyPriceMinor())) {
                best = new Upsell(tier.code(), tier.name(), tier.monthlyPriceMinor(), tier.yearlyPriceMinor(),
                        tier.trialDays(), saving, tier.currency());
            }
        }
        return best;
    }

    @Override
    public void recordWaivers(String customerId, String orderId, String currency, List<AppliedWaiver> waivers) {
        if (waivers == null || waivers.isEmpty() || customerId == null) {
            return;
        }
        Optional<Membership> found = memberships.liveOf(customerId);
        if (found.isEmpty()) {
            return;
        }
        Membership m = found.get();
        Instant now = Instant.now(clock);
        for (AppliedWaiver w : waivers) {
            if (w.amountMinor() <= 0) {
                continue;
            }
            activity.insertUse(new MembershipBenefitUse(UUID.randomUUID(), m.id(), customerId, m.tierCode(),
                    w.benefit(), orderId, w.ref(), Money.of(w.amountMinor(), currency),
                    "Fee waived by the " + m.tierCode() + " membership", now));
        }
    }

    // ------------------------------------------------------------------ cashback

    @Override
    public void onOrderDelivered(String orderId) {
        try {
            tx.serializable(() -> {
                Optional<Payment> payment = payments.findByOrderId(orderId);
                if (payment.isEmpty() || payment.get().shopperId() == null) {
                    return null;
                }
                Payment p = payment.get();
                Optional<Membership> found = memberships.liveOf(p.shopperId());
                if (found.isEmpty()) {
                    return null;
                }
                Optional<MembershipTier> tier = tiers.find(found.get().tierCode());
                if (tier.isEmpty() || !tier.get().has(BenefitCode.CASHBACK)) {
                    return null;
                }
                credits.cashback(found.get(), tier.get(), orderId, goodsOf(p), Instant.now(clock));
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Membership cashback for order {} could not be granted now; the sweep will retry: {}", orderId,
                    e.toString());
        }
    }

    /** The goods the shopper kept: item amounts less what was refunded (shipping and tax earn nothing). */
    private static long goodsOf(Payment p) {
        List<PaymentLine> lines = new ArrayList<>(p.lines());
        return lines.stream().mapToLong(l -> l.line().items().minor() - l.refundedItems().minor()).sum();
    }
}
