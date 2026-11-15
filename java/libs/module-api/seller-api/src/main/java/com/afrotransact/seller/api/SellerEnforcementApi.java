package com.afrotransact.seller.api;

/**
 * What the trust-ops module asks seller in-process to enforce a decision on an organisation: suspend it or lift the suspension
 * ({@code POST /v1/orgs/{orgId}/suspend} and {@code /reinstate}, scope {@code sellers:enforce}). Both are idempotent. Entered
 * under the caller's in-process principal; a caller without the scope is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403; an unknown organisation is 404.
 */
public interface SellerEnforcementApi {

    /** @param reasonCode the suspension reason's name (for example POLICY_VIOLATION), as seller names it */
    void suspend(String orgId, String reasonCode, String note);

    void reinstate(String orgId, String note);
}
