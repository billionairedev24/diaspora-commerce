package com.afrotransact.catalog.domain.tax;

/**
 * The decision summary tax-returns returned for a variant, kept on the variant. Display and audit only: checkout
 * reads the stored decision from tax-returns, never this copy.
 */
public record TaxDecision(String outcome, String internalTaxCode, String rowId, String rateDisplay,
                          String ruleVersion, String reason) {
}
