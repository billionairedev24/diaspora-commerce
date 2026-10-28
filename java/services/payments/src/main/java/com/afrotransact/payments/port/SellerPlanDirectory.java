package com.afrotransact.payments.port;

import java.util.Optional;

/**
 * The seller service's view of an organisation's subscription ({@code GET /v1/orgs/{orgId}/billing}, client
 * {@code payments-service} with scope {@code seller:billing}): the fallback when payments has not received a
 * {@code seller.subscription.changed} for the organisation yet.
 */
public interface SellerPlanDirectory {

    /**
     * The organisation's plan and subscription status; {@code Optional.of(new Plan(null, null))} when the seller service
     * says it has no subscription; empty when the seller service could not be asked (unconfigured or unavailable), so
     * the caller does not remember the answer.
     */
    Optional<Plan> currentPlan(String orgId);

    /** A plan code (null = none) and the subscription status. */
    record Plan(String planCode, String status) {
    }
}
