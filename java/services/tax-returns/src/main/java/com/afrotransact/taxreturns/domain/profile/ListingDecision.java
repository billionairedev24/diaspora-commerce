package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The stored decision for a variant ATIN under one seller behaviour (the behaviour hash). Several sellers may offer a
 * variant; those with the same behaviour share one row.
 *
 * @param atin           variant ATIN
 * @param behaviourHash  hash of the seller behaviour facts
 * @param sellerBehaviour the behaviour facts
 * @param sellerOrgIds   sellers seen with this behaviour
 * @param categoryCode   category used
 * @param factsSnapshot  the facts the decision was made from
 * @param status         decision status
 * @param outcome        outcome, null when none
 * @param internalTaxCode outcome code, null when none
 * @param providerCodes  provider codes at decision time
 * @param ruleVersion    rule version
 * @param reason         plain explanation
 * @param missingFacts   facts still needed
 * @param decidedAt      when
 * @param decidedBy      who or what decided (system:resolve, reviewer subject)
 * @param approvedBy     reviewer of the record at that time
 */
public record ListingDecision(String atin, String behaviourHash, Map<String, Object> sellerBehaviour,
                              List<String> sellerOrgIds, String categoryCode, Map<String, Object> factsSnapshot,
                              ResolveStatus status, Outcome outcome, String internalTaxCode,
                              Map<String, String> providerCodes, String ruleVersion, String reason,
                              List<MissingFact> missingFacts, Instant decidedAt, String decidedBy, String approvedBy) {

    public ListingDecision {
        sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        sellerOrgIds = sellerOrgIds == null ? List.of() : List.copyOf(sellerOrgIds);
        factsSnapshot = factsSnapshot == null ? Map.of() : Map.copyOf(factsSnapshot);
        providerCodes = providerCodes == null ? Map.of() : Map.copyOf(providerCodes);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }

    /** Same decision as {@code other}, ignoring who/when and the sellers seen. */
    public boolean sameDecision(ListingDecision other) {
        return status == other.status && outcome == other.outcome
                && Objects.equals(internalTaxCode, other.internalTaxCode)
                && Objects.equals(ruleVersion, other.ruleVersion)
                && providerCodes.equals(other.providerCodes) && factsSnapshot.equals(other.factsSnapshot)
                && Objects.equals(categoryCode, other.categoryCode);
    }
}
