package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
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
     * What the stored decision of each (variant ATIN, seller) is right now, read-only: the same record and rules the
     * quote reads, without a ship-to, without ingesting a missing record and without storing anything. The catalog
     * publish gate asks this, so tax-returns' stored decision is the one authority for "may this be listed". An ATIN
     * with no record is MISSING_FACTS: never guessed. Keyed by request key.
     */
    Map<String, ListingStatus> status(List<Request> items, LocalDate saleDate);

    /**
     * Decides one seller's listing of a variant and, when the outcome is chargeable, stores that decision for the seller (the
     * same stored row a quote writes), so "does this seller have a decision?" has a stored answer. Used when an offer of a
     * seller who is not the page owner is created (S384). A variant without a record is asked of catalog once and ingested for
     * review, exactly as a quote does; it is never guessed. Nothing is stored unless the status is RESOLVED.
     */
    ListingStatus decideListing(Request item, LocalDate saleDate);

    /** @param status RESOLVED is the only chargeable status; {@code reason} is plain words (null when resolved) */
    record ListingStatus(ResolveStatus status, String recordStatus, String reason) {

        public boolean resolved() {
            return status == ResolveStatus.RESOLVED;
        }
    }

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
