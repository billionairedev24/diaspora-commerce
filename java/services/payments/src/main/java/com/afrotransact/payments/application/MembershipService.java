package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.BillingInterval;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipCharge;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.domain.MembershipTier;

/**
 * A shopper's membership (S323, ADR 0006 section 4): join from checkout or the account on a saved card, see the tier,
 * renewal date and benefits used, upgrade (now, paying the difference for the rest of the period), downgrade or cancel
 * (the benefits run to the end of the period that was paid), and the fee refund rule. Everything for one customer: the
 * customer's token, or staff reading (payments:read).
 */
public interface MembershipService {

    /** The signed-in shopper's membership (the live one, else the latest that ended, else none). */
    MemberView me();

    /**
     * Joins a published tier on a saved card (the default one unless named). A first-time member gets the tier's trial
     * (one per customer and per card); otherwise the first period is charged now. A member on complimentary days
     * continues as a paid member when the days are over. Idempotent per Idempotency-Key.
     */
    MemberView join(JoinCommand command, String idempotencyKey);

    /**
     * A move to another tier or interval. A higher tier takes effect now and charges the difference for the rest of the
     * period (or, when the interval changes, a new period less the unused time); a lower tier or another interval is
     * scheduled for the end of the paid period. Choosing the current plan again cancels a scheduled change.
     */
    MemberView change(JoinCommand command, String idempotencyKey);

    /**
     * Cancels: no more charges, the benefits run to the end of the paid period. With {@code refund} the fee of the
     * latest period goes back to the card and the membership ends now, which the refund rule allows only within the
     * staff-set window and before any benefit was used.
     */
    MemberView cancel(boolean refund);

    /** Takes back a cancellation or a scheduled change before the period ends. */
    MemberView resume();

    /** The benefits the signed-in member used, newest first. */
    List<MembershipBenefitUse> myUses(int limit);

    /** One customer's membership as the member sees it, for staff (payments:read). */
    MemberView ofCustomer(String customerId);

    /** Staff list of memberships, newest first; filters optional (payments:read). */
    List<Membership> members(String tierCode, MembershipStatus status, Instant before, int limit);

    /** The timeline of a membership, oldest first (payments:read). */
    List<MembershipEvent> timeline(UUID membershipId);

    /**
     * Staff refund of one membership charge to the card with a reason (payments:configure). When the charge paid the
     * current period of a live membership, the membership ends now.
     */
    MembershipCharge refund(UUID chargeId, String reason);

    /**
     * @param tierCode        the tier to join or move to
     * @param interval        MONTHLY or YEARLY
     * @param paymentMethodId the saved card to bill; null for the Stripe Customer's default
     */
    record JoinCommand(String tierCode, BillingInterval interval, String paymentMethodId) {
    }

    /**
     * A membership as its owner sees it.
     *
     * @param membership     null when the customer never joined
     * @param tier           the membership's tier (null without membership)
     * @param pendingTier    the tier a scheduled change moves to
     * @param charges        the fee charges, newest first (up to 20)
     * @param usage          the benefits used, summed per benefit
     * @param refund         what the refund rule allows right now
     * @param trialAvailable whether joining a tier with a trial would start one for this customer
     * @param card           the saved card billed, if any
     */
    record MemberView(Membership membership, MembershipTier tier, MembershipTier pendingTier,
                      List<MembershipCharge> charges, List<BenefitUsage> usage, RefundRule refund,
                      boolean trialAvailable, Card card, int refundWindowDays) {
    }

    record BenefitUsage(BenefitCode benefit, long uses, long amountMinor, String currency) {
    }

    /**
     * The refund rule applied to the latest fee: full refund when canceling within {@code windowDays} of the charge and
     * before any benefit was used.
     *
     * @param reason OK, NOT_PAID (no fee charged yet), WINDOW_PASSED, BENEFIT_USED, DISABLED or ALREADY_REFUNDED
     * @param until  the end of the refund window of the latest charge
     */
    record RefundRule(boolean eligible, String reason, long amountMinor, String currency, Instant until) {
    }

    record Card(String paymentMethodId, String brand, String last4, Integer expMonth, Integer expYear) {
    }
}
