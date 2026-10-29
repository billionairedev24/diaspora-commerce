package com.afrotransact.payments.domain;

import java.util.Map;

/**
 * What one side earns: {@code creditMinor} of AfroTransact credit, or {@code membershipDays} of free membership. The
 * amount that does not belong to the type is zero.
 */
public record ReferralReward(RewardType type, long creditMinor, int membershipDays) {

    /** A reward cannot be above 1,000.00 of credit or one year of membership: a sanity bound for a typing error. */
    public static final long MAX_CREDIT_MINOR = 100_000L;
    public static final int MAX_DAYS = 366;

    public ReferralReward {
        if (type == null) {
            throw new PaymentsRuleViolation("payments.referral-settings-invalid", "Choose the reward",
                    Map.of("type", "Required"));
        }
        if (type == RewardType.CREDIT && (creditMinor < 0 || creditMinor > MAX_CREDIT_MINOR || membershipDays != 0)) {
            throw new PaymentsRuleViolation("payments.referral-settings-invalid", "Invalid credit reward",
                    Map.of("creditMinor", "Between 0.00 and " + MAX_CREDIT_MINOR / 100 + ".00"));
        }
        if (type == RewardType.MEMBERSHIP_DAYS
                && (membershipDays < 0 || membershipDays > MAX_DAYS || creditMinor != 0)) {
            throw new PaymentsRuleViolation("payments.referral-settings-invalid", "Invalid membership reward",
                    Map.of("membershipDays", "Between 0 and " + MAX_DAYS + " days"));
        }
    }

    public static ReferralReward credit(long minor) {
        return new ReferralReward(RewardType.CREDIT, minor, 0);
    }

    public static ReferralReward days(int days) {
        return new ReferralReward(RewardType.MEMBERSHIP_DAYS, 0, days);
    }

    /** Nothing to grant (a zero amount switches that side off). */
    public boolean isEmpty() {
        return creditMinor == 0 && membershipDays == 0;
    }
}
