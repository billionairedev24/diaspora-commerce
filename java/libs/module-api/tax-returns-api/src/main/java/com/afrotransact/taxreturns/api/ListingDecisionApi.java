package com.afrotransact.taxreturns.api;

import java.util.List;
import java.util.Map;

/**
 * What offers asks tax-returns in-process before an offer may be live: the seller's stored decision on a variant
 * ({@code GET /v1/tax/listing-decisions}, scope {@code tax:read}) and the request to decide from the page's facts
 * ({@code POST /v1/tax/listing-decisions/request}, scope {@code tax:classify}). Every call runs under an in-process caller
 * principal; the same permission checks as the endpoints apply and a caller without the scope is refused with the platform's
 * {@code ForbiddenActionException} (HTTP 403).
 */
public interface ListingDecisionApi {

    /** What is stored for this seller on this variant ATIN, read-only. */
    DecisionState find(String atin, String sellerOrgId);

    /** Decides for the seller from the page's facts and stores the decision when RESOLVED; idempotent. */
    DecisionState request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour);

    /**
     * @param status   RESOLVED, MISSING or the unresolved status (MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE, CONFLICT_OPEN)
     * @param resolved true only for RESOLVED with a stored decision of this organisation
     */
    record DecisionState(String status, boolean resolved, List<String> reasons) {

        public DecisionState {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
