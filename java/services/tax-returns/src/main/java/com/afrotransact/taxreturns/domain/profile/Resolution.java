package com.afrotransact.taxreturns.domain.profile;

import java.util.List;
import java.util.Map;

/**
 * What the tax rules say about a category and its facts. Consumers act on {@code status}, never on {@code outcome}:
 * only RESOLVED may be published or charged.
 *
 * @param status          resolve status
 * @param outcome         matched row's outcome, null when no row matched
 * @param internalTaxCode outcome-level code, null when no row matched
 * @param providerCodes   provider code per provider for this outcome (a provider without a code is absent)
 * @param rowId           matched decision row, e.g. {@code S007}
 * @param rateDisplay     display only
 * @param missingFacts    facts still needed
 * @param ruleVersion     rule version the decision was made under
 * @param reason          plain explanation
 * @param openDecision    open decision number (1 to 5) behind a gate, 0 = none
 */
public record Resolution(ResolveStatus status, Outcome outcome, String internalTaxCode,
                         Map<String, String> providerCodes, String rowId, String rateDisplay,
                         List<MissingFact> missingFacts, String ruleVersion, String reason, int openDecision) {

    public Resolution {
        providerCodes = providerCodes == null ? Map.of() : Map.copyOf(providerCodes);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }

    public Resolution withProviderCodes(Map<String, String> codes) {
        return new Resolution(status, outcome, internalTaxCode, codes, rowId, rateDisplay, missingFacts, ruleVersion,
                reason, openDecision);
    }

    public Resolution withStatus(ResolveStatus next, String why) {
        return new Resolution(next, outcome, internalTaxCode, providerCodes, rowId, rateDisplay, missingFacts,
                ruleVersion, why, openDecision);
    }

    public boolean chargeable() {
        return status.chargeable();
    }
}
