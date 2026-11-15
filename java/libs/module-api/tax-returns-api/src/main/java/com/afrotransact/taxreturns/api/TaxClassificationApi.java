package com.afrotransact.taxreturns.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What other modules may ask tax-returns in-process: the two read-only calls the catalog publish gate makes over HTTP today
 * ({@code POST /v1/tax/classifications/resolve} and {@code POST /v1/tax/listing-decisions/status}, scope {@code tax:classify}).
 *
 * <p>Every call must run under an in-process caller principal ({@code com.afrotransact.module.InProcessCalls}); the same
 * permission checks as the HTTP endpoints apply and a caller without the scope is refused.
 */
public interface TaxClassificationApi {

    /**
     * Pure rules evaluation, never charges.
     *
     * @throws UnknownTaxProfileException when tax-returns has no profile for the category (HTTP 404)
     */
    Resolution resolve(ResolveRequest request);

    /** Batched, read-only: the stored decision status of each variant ATIN, keyed by the ATIN as upper-cased by the module. */
    Map<String, ListingStatus> listingStatus(Collection<ListingQuery> queries);

    /**
     * @param facts           the variant's product facts by fact id
     * @param sellerBehaviour the seller's behaviour facts by fact id
     */
    record ResolveRequest(String categoryCode, String productTypeCode, Map<String, Object> facts,
                          Map<String, Object> sellerBehaviour) {

        public ResolveRequest {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }
    }

    /**
     * @param status RESOLVED, MISSING_FACTS, BLOCKED, CONFLICT_OPEN, NEEDS_APPROVAL or REFINE_CODE
     */
    record Resolution(String status, String outcome, String internalTaxCode, String rowId, String rateDisplay,
                      List<MissingFact> missingFacts, String ruleVersion, String reason) {

        public Resolution {
            missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        }
    }

    record MissingFact(String factId, String label, String message) {
    }

    /** @param sellerOrgId the listing owner, whose behaviour the decision is read under */
    record ListingQuery(String atin, String sellerOrgId) {
    }

    /**
     * @param decisionStatus RESOLVED, MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE or CONFLICT_OPEN
     * @param recordStatus   the record status (APPROVED, PENDING_REVIEW ...) or null when there is no record
     */
    record ListingStatus(String atin, String decisionStatus, String recordStatus, String reason) {
    }

    /** tax-returns has no profile for the category. */
    class UnknownTaxProfileException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownTaxProfileException(String categoryCode) {
            super("No tax profile for category " + categoryCode);
        }
    }
}
