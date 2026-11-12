package com.afrotransact.offers.port;

import java.util.List;
import java.util.Map;

/**
 * tax-returns' stored tax decision of a selling organisation on a variant ATIN (S384). The decision of the page's owner is
 * not another seller's: an offer may be live only when this seller has its own RESOLVED decision.
 */
public interface TaxDecisions {

    /**
     * The organisation's stored decision on the ATIN; read-only, never creates one.
     *
     * @throws TaxUnavailable when tax-returns cannot be asked: callers hold the offer rather than guess
     */
    DecisionState find(String atin, String sellerOrgId);

    /**
     * Asks tax-returns to decide for this organisation from the page's facts and the seller's behaviour answers. It never
     * defaults a rate: when the record is not approved or a fact is missing, nothing is stored and the state says why.
     *
     * @throws TaxUnavailable when tax-returns cannot be asked
     */
    DecisionState request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour);

    /**
     * @param status   RESOLVED, MISSING (no stored decision) or an unresolved status (MISSING_FACTS, BLOCKED,
     *                 NEEDS_APPROVAL, REFINE_CODE, CONFLICT_OPEN)
     * @param resolved true only for a stored RESOLVED decision of this organisation
     * @param reasons  plain reasons when not resolved
     */
    record DecisionState(String status, boolean resolved, List<String> reasons) {

        public DecisionState {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /** tax-returns could not be reached or answered with a server error. */
    class TaxUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TaxUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
