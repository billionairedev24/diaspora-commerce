package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The stored tax decision of one selling organisation on one variant ATIN (S384). The decision of the page's owner is not
 * another seller's: offers-service asks here before an offer may be live, and asks tax-returns to decide for the seller
 * from the page's facts when there is none. Nothing here ever defaults a rate or an outcome.
 */
public interface SellerListingDecisionService {

    /**
     * What is stored for this seller on this ATIN, read-only (tax:classify, tax:read or tax:classify-admin). RESOLVED only
     * when the classification record is APPROVED and a stored RESOLVED decision lists the organisation under the rules in
     * force now; MISSING when nothing is stored; otherwise why the record cannot be charged.
     */
    State find(String atin, String sellerOrgId);

    /**
     * Decides for the seller from the page's facts and the seller's behaviour answers and stores the decision when it is
     * RESOLVED (tax:classify). Idempotent: asking again returns the stored decision. When the record is not approved or a fact
     * is missing nothing is stored and the state says why.
     */
    State request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour);

    /**
     * @param status   RESOLVED, MISSING, or the unresolved status (MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE,
     *                 CONFLICT_OPEN)
     * @param resolved true only for RESOLVED with a stored decision of this organisation
     * @param outcome  EXEMPT, TAXABLE or BLOCKED of the stored decision, else null
     */
    record State(String atin, String sellerOrgId, String status, boolean resolved, String outcome, String internalTaxCode,
                 String ruleVersion, Instant decidedAt, List<String> reasons) {

        public State {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
