package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.FeeWaivers;
import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.domain.TierBenefit;

/**
 * What a membership gives, as the rest of the platform asks for it (S323): the delivery fee waivers checkout applies
 * through the fee rule resolution of ADR 0005 (and the cart and checkout preview), the entitlements other services act
 * on (early access, priority support, boosted rewards), recording the benefits used on an order, and the
 * cashback on a delivered order.
 */
public interface MembershipBenefitsService {

    /** What the customer is entitled to now; for orders-service, delivery-runs, support tools and the referral program. */
    Entitlements entitlementsOf(String customerId);

    /**
     * The extra share, in hundredths of a percent, a customer's referral rewards are bigger by because of the
     * membership tier (the BOOSTED_REWARDS benefit; 0 for a non-member or during a trial). In-process: the referral
     * program asks it while granting a reward, no access check.
     */
    int rewardBoostBps(String customerId);

    /** The signed-in shopper's own entitlements (customer token). */
    Entitlements myEntitlements();

    /**
     * The fees a customer's membership waives on an order, for checkout (calling services). Pure: nothing is recorded
     * until the order's payment is created with the waivers.
     */
    WaiverQuote quote(String customerId, long itemsMinor, String currency, List<FeeWaivers.Fee> fees);

    /**
     * The same for the signed-in shopper (cart and checkout preview). A shopper who is not a member also gets the best
     * offer: what joining would save on this order.
     */
    WaiverQuote quoteMine(long itemsMinor, String currency, List<FeeWaivers.Fee> fees);

    /**
     * Records the benefits applied to an order that is being paid (called by payments while creating the order's
     * payment, in its transaction). Idempotent per benefit, order and ref.
     */
    void recordWaivers(String customerId, String orderId, String currency, List<AppliedWaiver> waivers);

    /**
     * An order was delivered: grants the member's cashback on it once. Never throws (the delivery notice must not fail
     * because of a loyalty benefit); a missed grant is picked up by the sweep.
     */
    void onOrderDelivered(String orderId);

    /**
     * What the customer's membership gives right now.
     *
     * @param member         false when the customer has no live membership (then everything else is empty / zero)
     * @param benefits       the benefits that apply now (credit benefits only after the first paid period)
     * @param earlyAccessHours hours before the public opening members can order from pre-order windows and runs
     * @param rewardBoostBps extra share of referral and gift rewards, in hundredths of a percent
     */
    record Entitlements(boolean member, String customerId, String tierCode, String tierName, MembershipStatus status,
                        List<TierBenefit> benefits, boolean prioritySupport,
                        boolean memberPrices, int earlyAccessHours, int rewardBoostBps) {

        public boolean has(BenefitCode code) {
            return benefits.stream().anyMatch(b -> b.code() == code);
        }
    }

    /**
     * Fees waived on an order and, for a shopper who is not a member, what joining would save.
     *
     * @param tierCode / tierName the member's tier when {@code member}
     * @param cashbackMinor the credit the member earns on this order once it is delivered ("credit earned"): 0 without
     *                      the benefit, under its order minimum, or during a trial (credit benefits start with the first
     *                      paid period)
     */
    record WaiverQuote(boolean member, String tierCode, String tierName, List<FeeWaivers.Waiver> waivers,
                       long totalWaivedMinor, long cashbackMinor, Upsell upsell) {
    }

    /** "Join Plus to save $X on this order". */
    record Upsell(String tierCode, String tierName, long monthlyPriceMinor, long yearlyPriceMinor, int trialDays,
                  long savingMinor, String currency) {
    }

    /** A fee waived on an order: the shipment it belonged to, the benefit and the amount. */
    record AppliedWaiver(String ref, BenefitCode benefit, long amountMinor, String tierCode) {
    }
}
