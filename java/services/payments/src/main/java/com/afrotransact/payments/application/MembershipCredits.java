package com.afrotransact.payments.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.TierBenefit;
import com.afrotransact.payments.port.MembershipActivityRepository;
import org.springframework.stereotype.Component;

/**
 * The credit benefits of a membership, granted through the credit ledger (source MEMBERSHIP). Each grant is idempotent:
 * cashback once per order, the monthly credit once per month of the membership. Credit benefits are for paid periods
 * only (never a trial) and the credit can only buy products and services (ADR 0006). Call inside a SERIALIZABLE
 * transaction.
 */
@Component
class MembershipCredits {

    static final String ACTOR = "membership";

    private final CreditLedger ledger;
    private final MembershipActivityRepository activity;

    MembershipCredits(CreditLedger ledger, MembershipActivityRepository activity) {
        this.ledger = ledger;
        this.activity = activity;
    }

    /** Which month of the membership {@code now} is in: 0 for the first month. */
    static long monthIndex(Membership m, Instant now) {
        return Math.max(0, ChronoUnit.MONTHS.between(m.startedAt().atZone(ZoneOffset.UTC),
                now.atZone(ZoneOffset.UTC)));
    }

    /** The monthly credit of this month, once. Returns the amount granted, empty when none was due. */
    Optional<Money> monthly(Membership m, MembershipTier tier, Instant now) {
        Optional<TierBenefit> benefit = tier.benefit(BenefitCode.MONTHLY_CREDIT);
        if (benefit.isEmpty() || !m.creditBenefitsActive()) {
            return Optional.empty();
        }
        long index = monthIndex(m, now);
        Money amount = Money.of(benefit.get().amountMinor(), tier.currency());
        MembershipBenefitUse use = new MembershipBenefitUse(UUID.randomUUID(), m.id(), m.customerId(), tier.code(),
                BenefitCode.MONTHLY_CREDIT, null, "month-" + index, amount, "Monthly credit, month " + (index + 1),
                now);
        if (!activity.insertUse(use)) {
            return Optional.empty();
        }
        ledger.add(new CreditLedger.Add(m.customerId(), CreditKind.GRANT, amount, CreditSource.MEMBERSHIP,
                "Membership monthly credit (" + tier.name() + ")", ACTOR, null, m.id().toString(),
                "membership:monthly:" + m.id() + ":" + index, true));
        return Optional.of(amount);
    }

    /**
     * Cashback on a delivered order: the tier's percent of the goods, up to its per-order cap, once per order. Empty
     * when the member's tier has no cashback, the order is under its minimum, or it was already granted.
     */
    Optional<Money> cashback(Membership m, MembershipTier tier, String orderId, long goodsMinor, Instant now) {
        Optional<TierBenefit> benefit = tier.benefit(BenefitCode.CASHBACK);
        if (benefit.isEmpty() || !m.creditBenefitsActive() || goodsMinor < benefit.get().minOrder()) {
            return Optional.empty();
        }
        long amountMinor = Math.multiplyExact(goodsMinor, (long) benefit.get().percentBps()) / 10_000L;
        if (benefit.get().capMinor() != null) {
            amountMinor = Math.min(amountMinor, benefit.get().capMinor());
        }
        if (amountMinor < 1) {
            return Optional.empty();
        }
        Money amount = Money.of(amountMinor, tier.currency());
        MembershipBenefitUse use = new MembershipBenefitUse(UUID.randomUUID(), m.id(), m.customerId(), tier.code(),
                BenefitCode.CASHBACK, orderId, "", amount, benefit.get().percentBps() / 100.0 + "% of goods", now);
        if (!activity.insertUse(use)) {
            return Optional.empty();
        }
        ledger.add(new CreditLedger.Add(m.customerId(), CreditKind.GRANT, amount, CreditSource.MEMBERSHIP,
                "Membership cashback on order " + orderId + " (" + tier.name() + ")", ACTOR, orderId,
                m.id().toString(), "membership:cashback:" + orderId, true));
        return Optional.of(amount);
    }
}
