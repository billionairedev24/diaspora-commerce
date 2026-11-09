package com.afrotransact.taxreturns.domain.profile;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * One row of a category's decision table. Rows are tried top to bottom (they are also mutually exclusive).
 *
 * @param id              {@code S001} to {@code S105}, the Appendix A scenario number
 * @param scenario        scenario number
 * @param when            condition over the required facts, null = always
 * @param condition       the source document's wording
 * @param outcome         EXEMPT, TAXABLE or BLOCKED
 * @param internalTaxCode outcome-level code, {@code AT.<CATEGORY>.<OUTCOME_SLUG>}
 * @param providerCodes   provider code per provider as the dataset shipped it (STRIPE filled; a provider without a code is left out)
 * @param rateDisplay     display only, never used to charge
 * @param note            where the document's wording needed an interpretation
 */
public record DecisionRow(String id, int scenario, Condition when, String condition, Outcome outcome,
                          String internalTaxCode, Map<String, String> providerCodes, String rateDisplay, String note) {

    public DecisionRow {
        providerCodes = providerCodes == null ? Map.of() : providerCodes.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public boolean matches(Map<String, Object> facts) {
        return when == null || when.holds(facts);
    }
}
