package com.afrotransact.payments.adapter.out.membership;

import com.afrotransact.payments.application.MembershipRewardGrants;
import com.afrotransact.payments.port.MembershipGrants;
import org.springframework.stereotype.Component;

/**
 * The referral program's way to give free membership days (S323): the membership program grants them, idempotently per
 * reward. Without a published tier there is nothing to give days of, so the reward stays DEFERRED and the reward job tries
 * again.
 */
@Component
class MembershipProgramGrants implements MembershipGrants {

    private final MembershipRewardGrants rewards;

    MembershipProgramGrants(MembershipRewardGrants rewards) {
        this.rewards = rewards;
    }

    @Override
    public Outcome grant(String customerId, int days, String reason, String idempotencyKey) {
        return rewards.grantEntryTierDays(customerId, days, reason, idempotencyKey) ? Outcome.GRANTED
                : Outcome.UNAVAILABLE;
    }
}
