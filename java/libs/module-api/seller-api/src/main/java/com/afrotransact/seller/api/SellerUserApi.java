package com.afrotransact.seller.api;

import com.afrotransact.module.EndUserActor;

/**
 * Seller reads for the signed-in person (MCP tools): an organisation's account health and the staff review queue of onboarding
 * applications. The in-process equivalent of relaying their bearer token to seller-service: seller's own permission and organisation
 * checks decide as for the HTTP request, and the application detail is always the masked view (as the HTTP adapter asks for it). The
 * calling module needs the delegation scope {@value #READ_SCOPE}. Results are the JSON tree of the HTTP response; failures are
 * {@code ModuleApiException} with the HTTP problem's status and code.
 */
public interface SellerUserApi {

    /** Delegation scope for this API. */
    String READ_SCOPE = "seller:user.read";

    /** {@code GET /v1/orgs/{orgId}/account-health}. */
    Object accountHealth(EndUserActor user, String orgId);

    /** {@code GET /v1/applications}: the review queue; {@code status} is comma separated. */
    Object applicationQueue(EndUserActor user, String status, int page, int size);

    /** {@code GET /v1/applications/{id}?pii=MASKED}. */
    Object application(EndUserActor user, String applicationId);
}
