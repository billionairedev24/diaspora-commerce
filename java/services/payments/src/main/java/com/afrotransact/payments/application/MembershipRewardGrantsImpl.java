package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.port.MembershipTierRepository;
import org.springframework.stereotype.Service;

@Service
class MembershipRewardGrantsImpl implements MembershipRewardGrants {

    private final MembershipTierRepository tiers;
    private final MembershipGrants grants;

    MembershipRewardGrantsImpl(MembershipTierRepository tiers, MembershipGrants grants) {
        this.tiers = tiers;
        this.grants = grants;
    }

    @Override
    public boolean grantEntryTierDays(String customerId, int days, String reason, String idempotencyKey) {
        List<MembershipTier> published = tiers.published();
        if (published.isEmpty()) {
            return false;
        }
        grants.grantDays(customerId, published.get(0).code(), days, reason, idempotencyKey);
        return true;
    }
}
