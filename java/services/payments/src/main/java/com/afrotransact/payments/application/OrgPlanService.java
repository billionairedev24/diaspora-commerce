package com.afrotransact.payments.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Which plan each organisation is on, so the fee schedule's plan rates apply: a projection of the seller service's
 * {@code seller.subscription.changed}, with the seller service's billing API as the fallback while no event arrived.
 */
public interface OrgPlanService {

    /** Applies a subscription event (inbox de-duplicated; older events than the stored state are ignored). */
    boolean subscriptionChanged(SubscriptionNotice notice);

    /** The organisation's plan code, empty when it has none or it cannot be known now. */
    Optional<String> planOf(String orgId);

    /**
     * The plans of several organisations (organisations without a plan are left out). Reads the projection and asks
     * the seller service once for each organisation it has never heard of; call it outside money transactions.
     */
    Map<String, String> plansOf(Collection<String> orgIds);
}
