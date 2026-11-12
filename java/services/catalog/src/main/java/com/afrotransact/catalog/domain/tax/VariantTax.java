package com.afrotransact.catalog.domain.tax;

import java.util.List;
import java.util.Map;

/**
 * The tax state of one variant: its facts, the gate status, what is still missing and the decision summary.
 *
 * @param facts   the variant's own tax facts (owner VARIANT), typed
 * @param decision null until the rules resolved
 */
public record VariantTax(Map<String, Object> facts, TaxStatus status, List<MissingTaxFact> missing,
                         TaxDecision decision) {

    /** A variant that has not been checked yet. */
    public static final VariantTax NONE = new VariantTax(Map.of(), TaxStatus.MISSING_FACTS, List.of(), null);

    public VariantTax {
        facts = facts == null ? Map.of() : facts;
        status = status == null ? TaxStatus.MISSING_FACTS : status;
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /**
     * True only for a variant with a stored, resolved decision: the gate status is READY <em>and</em> the rules returned a
     * decision. A READY status without a decision is never trusted (never guess): such a variant cannot be live.
     */
    public boolean ready() {
        return status == TaxStatus.READY && decision != null;
    }

    public VariantTax withStatus(TaxStatus next) {
        return new VariantTax(facts, next, missing, decision);
    }

    public VariantTax withFacts(Map<String, Object> next) {
        return new VariantTax(next, status, missing, decision);
    }
}
