package com.afrotransact.payments.application;

/**
 * The membership side of the referral program (S322, S323): free membership days as a reward. The reward does not name a
 * tier, so the days are of the entry tier: the published tier with the lowest sort order.
 */
public interface MembershipRewardGrants {

    /**
     * Grants {@code days} of the entry tier, once per {@code idempotencyKey} (a replay adds nothing).
     *
     * @return false when there is no published tier to give days of (the reward stays deferred and is retried)
     */
    boolean grantEntryTierDays(String customerId, int days, String reason, String idempotencyKey);
}
