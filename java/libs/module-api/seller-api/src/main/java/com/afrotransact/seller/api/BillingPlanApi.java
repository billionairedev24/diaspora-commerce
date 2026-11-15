package com.afrotransact.seller.api;

import java.util.Optional;

/**
 * What the payments module asks seller in-process: the plan an organisation is on, to price its commission
 * ({@code GET /v1/orgs/{orgId}/billing}, scope {@code seller:billing}). Entered under the caller's in-process principal; a
 * caller without the scope is refused with {@code com.afrotransact.module.ModuleApiException} status 403.
 */
public interface BillingPlanApi {

    /** @return the organisation's plan and subscription status, empty when it has no billing record (HTTP 404) */
    Optional<Plan> currentPlan(String orgId);

    /** @param planCode the plan's code, null when none; @param status the subscription status name */
    record Plan(String planCode, String status) {
    }
}
