package com.afrotransact.payments.port;

import java.util.Optional;

import com.afrotransact.payments.domain.OrgPlan;

/** payments' projection of organisations' current plans (seller.subscription.changed). */
public interface OrgPlanRepository {

    Optional<OrgPlan> find(String orgId);

    /**
     * Stores the plan unless the stored one is newer ({@code occurredAt}); an event always replaces a fallback read of
     * the same moment. Returns whether it was stored.
     */
    boolean upsertIfNewer(OrgPlan plan);
}
