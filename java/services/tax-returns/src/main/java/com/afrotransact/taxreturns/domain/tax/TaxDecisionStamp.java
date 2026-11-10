package com.afrotransact.taxreturns.domain.tax;

/**
 * The stored tax decision a sale was priced under, as orders snapshotted it on the line: refunds reverse with it.
 *
 * @param outcome          EXEMPT or TAXABLE
 * @param internalTaxCode  outcome-level code
 * @param ruleVersion      rule version of the decision
 * @param addressPrecision ZIP or ADDRESS, null when not reported
 */
public record TaxDecisionStamp(String outcome, String internalTaxCode, String ruleVersion, String addressPrecision) {

    public boolean present() {
        return internalTaxCode != null && !internalTaxCode.isBlank();
    }
}
