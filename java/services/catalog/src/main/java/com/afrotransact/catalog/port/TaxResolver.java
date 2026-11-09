package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.tax.MissingTaxFact;

/**
 * Asks tax-returns what a category and its facts resolve to ({@code POST /v1/tax/classifications/resolve}), without
 * charging anything. Never guesses: missing facts give MISSING_FACTS and no outcome. Only RESOLVED may be published.
 */
public interface TaxResolver {

    Resolution resolve(Request request);

    /**
     * @param facts           the variant's product facts (owner VARIANT), by fact id
     * @param sellerBehaviour the seller's behaviour facts (owner OFFER), by fact id
     */
    record Request(String categoryCode, String productTypeCode, Map<String, Object> facts,
                   Map<String, Object> sellerBehaviour) {
    }

    /**
     * @param status RESOLVED, MISSING_FACTS, BLOCKED, CONFLICT_OPEN, NEEDS_APPROVAL or REFINE_CODE
     */
    record Resolution(String status, String outcome, String internalTaxCode, String rowId, String rateDisplay,
                      List<MissingTaxFact> missingFacts, String ruleVersion, String reason) {

        public Resolution {
            missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        }
    }

    /** tax-returns cannot be reached or failed: nothing is known about the tax of the item. */
    class TaxServiceUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TaxServiceUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** tax-returns has no profile for the category. */
    class TaxProfileNotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TaxProfileNotFound(String categoryCode) {
            super("No tax profile for category " + categoryCode);
        }
    }
}
