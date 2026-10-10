package com.afrotransact.orders.application;

/**
 * How checkout taxes orders ({@code at.orders.tax.*}).
 *
 * @param provider        {@code TAX_RETURNS} (quote from tax-returns-service) or {@code TABLE} (the interim per-state
 *                        table only)
 * @param fallbackToTable when tax-returns is unavailable or refuses, tax with the per-state table and log a warning
 *                        instead of failing checkout
 */
public record TaxSettings(String provider, boolean fallbackToTable) {

    public static final String TAX_RETURNS = "TAX_RETURNS";
    public static final String TABLE = "TABLE";

    public boolean quoteFromTaxReturns() {
        return TAX_RETURNS.equalsIgnoreCase(provider);
    }
}
