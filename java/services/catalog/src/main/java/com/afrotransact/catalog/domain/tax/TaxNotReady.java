package com.afrotransact.catalog.domain.tax;

import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;

/** The product cannot go live because a variant's tax gate is not READY; carries what to fix, per field, in plain words. */
public class TaxNotReady extends CatalogRuleViolation {

    private static final long serialVersionUID = 1L;

    private final transient TaxReadiness readiness;

    public TaxNotReady(String message, Map<String, String> fieldErrors, TaxReadiness readiness) {
        super(message, fieldErrors);
        this.readiness = readiness;
    }

    public TaxReadiness readiness() {
        return readiness;
    }
}
