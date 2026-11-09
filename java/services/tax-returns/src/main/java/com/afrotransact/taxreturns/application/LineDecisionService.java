package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;

/**
 * The stored tax decision of each quote line: the variant ATIN's classification record (approved by tax review) plus the
 * seller's behaviour, the ship-to and the sale date, resolved under the current rules. A line with no definite decision
 * is blocked; there is no default rate and no default outcome.
 */
public interface LineDecisionService {

    /** One decision per request key. Never throws for a line that cannot be decided: it comes back blocked. */
    Map<String, LineDecision> decide(List<Request> lines, TaxAddress shipTo, LocalDate saleDate);

    /**
     * @param key             caller's key (the line id)
     * @param atin            variant ATIN
     * @param sellerOrgId     seller of the line
     * @param sellerBehaviour the seller's behaviour facts (sold heated, utensils provided, seller_is_bakery ...)
     */
    record Request(String key, String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {

        public Request {
            sellerBehaviour = sellerBehaviour == null ? Map.of() : sellerBehaviour;
        }
    }
}
