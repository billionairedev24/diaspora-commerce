package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.PlanEntitlements;

/**
 * What an organisation's plan lets it do (live-listing cap, AI listing writer, Lola tier). Read by the organisation's
 * members, by staff with a billing permission, and by services holding {@code sellers:entitlements.read} — offers-service
 * checks the cap before a listing goes live.
 */
public interface EntitlementsService {

    String SERVICE_READ = "sellers:entitlements.read";

    /**
     * The entitlements of {@code orgId}'s plan. An organisation with no subscription (AfroTransact Retail, or one not
     * yet activated) is not capped.
     */
    Entitlements forOrg(String orgId);

    /**
     * @param planCode null when the organisation has no plan
     * @param plan     what the plan allows
     */
    record Entitlements(String orgId, String planCode, String planName, PlanEntitlements plan) {
    }
}
