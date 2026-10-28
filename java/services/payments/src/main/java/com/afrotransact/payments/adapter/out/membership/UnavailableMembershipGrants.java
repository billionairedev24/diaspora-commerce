package com.afrotransact.payments.adapter.out.membership;

import com.afrotransact.payments.port.MembershipGrants;
import org.springframework.stereotype.Component;

/**
 * TODO(S323): stand-in until the membership program lands. It grants nothing, so a referral reward of free membership
 * days stays DEFERRED and is retried by the reward job; replace this adapter with the membership service's client and
 * the deferred rewards are granted on the next run (the idempotency key makes that safe).
 */
@Component
class UnavailableMembershipGrants implements MembershipGrants {

    @Override
    public Outcome grant(String customerId, int days, String reason, String idempotencyKey) {
        return Outcome.UNAVAILABLE;
    }
}
