package com.afrotransact.payments.domain;

import java.util.Objects;

/**
 * One (sub-)account of the chart: {@code 2000:org_x} is the payable of seller org_x; unscoped accounts have an
 * empty key.
 */
public record LedgerAccount(Chart chart, String key) {

    public LedgerAccount {
        Objects.requireNonNull(chart, "chart");
        key = key == null ? "" : key.trim();
        if (chart.scope() == Chart.Scope.NONE && !key.isEmpty()) {
            throw new IllegalArgumentException("Account " + chart.code() + " has no sub-accounts");
        }
        if (chart.scope() != Chart.Scope.NONE && key.isEmpty()) {
            throw new IllegalArgumentException("Account " + chart.code() + " needs a " + chart.scope() + " key");
        }
    }

    public static LedgerAccount of(Chart chart) {
        return new LedgerAccount(chart, "");
    }

    public static LedgerAccount sellerPayable(String orgId) {
        return new LedgerAccount(Chart.SELLER_PAYABLE, orgId);
    }

    /** {@code 4400} AfroTransact Retail sales (first-party revenue, never a payable). */
    public static LedgerAccount retailSales() {
        return new LedgerAccount(Chart.RETAIL_SALES, "");
    }

    public static LedgerAccount chargebackReserve(String orgId) {
        return new LedgerAccount(Chart.CHARGEBACK_RESERVE, orgId);
    }

    public static LedgerAccount providerFeesReceivable(String orgId) {
        return new LedgerAccount(Chart.PROVIDER_FEES_RECEIVABLE, orgId);
    }

    public static LedgerAccount salesTax(String jurisdiction) {
        return new LedgerAccount(Chart.SALES_TAX_PAYABLE, jurisdiction);
    }

    /** {@code 1000} or {@code 2000:org_x}. */
    public String display() {
        return key.isEmpty() ? chart.code() : chart.code() + ":" + key;
    }
}
