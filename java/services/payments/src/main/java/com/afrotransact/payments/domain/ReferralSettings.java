package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staff-configurable referral program (Settings, Money or Growth). AfroTransact funds it. What the referrer earns and
 * what the friend earns are set separately. Changed without a release, each change with a reason (ADR 0006).
 *
 * @param enabled                   false stops new referrals and pauses granting (a kill switch)
 * @param minOrderMinor             the qualifying order must be worth at least this once delivered
 * @param returnWindowDays          rewards are granted this many days after the qualifying order was delivered
 * @param maxRewardedPerReferrer    referrals one customer can have rewarded; 0 is no limit
 * @param maxCreditPerCustomerMinor referral credit one customer can earn in all (either side); 0 is no limit
 * @param validityDays              days after sign-up in which the friend can still qualify
 */
public record ReferralSettings(boolean enabled, ReferralReward referrer, ReferralReward referred,
                               QualifyingEvent qualifyingEvent, long minOrderMinor, int returnWindowDays,
                               int maxRewardedPerReferrer, long maxCreditPerCustomerMinor, int validityDays,
                               String updatedBy, Instant updatedAt, long version) {

    /** The event that makes a referral qualify. */
    public enum QualifyingEvent {
        FIRST_ORDER_DELIVERED
    }

    public ReferralSettings {
        validate(referrer, referred, qualifyingEvent, minOrderMinor, returnWindowDays, maxRewardedPerReferrer,
                maxCreditPerCustomerMinor, validityDays);
    }

    private static void validate(ReferralReward referrer, ReferralReward referred, QualifyingEvent event, long minOrder,
                                 int returnWindow, int maxRewarded, long maxCredit, int validity) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (referrer == null) {
            errors.put("referrer", "Required");
        }
        if (referred == null) {
            errors.put("referred", "Required");
        }
        if (event == null) {
            errors.put("qualifyingEvent", "Required");
        }
        if (minOrder < 0 || minOrder > 1_000_000L) {
            errors.put("minOrderMinor", "Between 0.00 and 10,000.00");
        }
        if (returnWindow < 0 || returnWindow > 180) {
            errors.put("returnWindowDays", "Between 0 and 180 days");
        }
        if (maxRewarded < 0 || maxRewarded > 10_000) {
            errors.put("maxRewardedPerReferrer", "Between 0 and 10,000");
        }
        if (maxCredit < 0 || maxCredit > 100_000_000L) {
            errors.put("maxCreditPerCustomerMinor", "Between 0.00 and 1,000,000.00");
        }
        if (validity < 1 || validity > 730) {
            errors.put("validityDays", "Between 1 and 730 days");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.referral-settings-invalid", "Invalid referral settings", errors);
        }
    }

    /** The reward of a side. */
    public ReferralReward rewardOf(ReferralSide side) {
        return side == ReferralSide.REFERRER ? referrer : referred;
    }

    /** Whether an order worth {@code deliveredMinor} delivered qualifies. */
    public boolean qualifies(long deliveredMinor) {
        return deliveredMinor >= minOrderMinor;
    }
}
