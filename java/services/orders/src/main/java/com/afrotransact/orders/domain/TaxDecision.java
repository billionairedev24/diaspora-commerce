package com.afrotransact.orders.domain;

/**
 * The tax decision a sale used, snapshotted on the order line (tax-returns' stored decision, never a live recompute)
 * so refunds and returns reverse with the code the sale was taxed with.
 *
 * @param status           decision status of the quote line; only {@code RESOLVED} lines are ever sold
 * @param outcome          EXEMPT or TAXABLE
 * @param internalTaxCode  outcome-level internal tax code (AT.&lt;CATEGORY&gt;.&lt;OUTCOME&gt;)
 * @param providerTaxCode  the provider code mapped to the internal code at the time (null when none)
 * @param ruleVersion      version of the rule dataset that decided
 * @param provider         the tax method that priced the line, as reported by the quote (RULES, STRIPE_TAX, ...)
 * @param addressPrecision how exactly the jurisdiction was resolved (ZIP or ADDRESS); null when not reported
 */
public record TaxDecision(String status, String outcome, String internalTaxCode, String providerTaxCode,
                          String ruleVersion, String provider, String addressPrecision) {

    public static final String RESOLVED = "RESOLVED";

    /** True when tax-returns reported a definite decision for the line. */
    public boolean resolved() {
        return RESOLVED.equals(status) && ("EXEMPT".equals(outcome) || "TAXABLE".equals(outcome));
    }
}
