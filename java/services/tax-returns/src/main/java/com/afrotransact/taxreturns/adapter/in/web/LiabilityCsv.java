package com.afrotransact.taxreturns.adapter.in.web;

import java.math.BigDecimal;
import java.util.Currency;

import com.afrotransact.taxreturns.application.LiabilityReport;
import com.afrotransact.taxreturns.domain.tax.LiabilityRow;

/** CSV export of a liability report: one row per jurisdiction and currency, minor units and decimal amounts. */
final class LiabilityCsv {

    static final String HEADER = "period,seller_org_id,jurisdiction,currency,orders,gross_sales_minor,"
            + "taxable_sales_minor,tax_collected_minor,tax_refunded_minor,net_tax_minor,net_tax";

    private LiabilityCsv() {
    }

    static String write(LiabilityReport report) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (LiabilityRow r : report.rows()) {
            sb.append(report.month()).append(',')
                    .append(cell(report.sellerOrgId())).append(',')
                    .append(cell(r.jurisdiction())).append(',')
                    .append(cell(r.currency())).append(',')
                    .append(r.orders()).append(',')
                    .append(r.grossSalesMinor()).append(',')
                    .append(r.taxableSalesMinor()).append(',')
                    .append(r.taxCollectedMinor()).append(',')
                    .append(r.taxRefundedMinor()).append(',')
                    .append(r.netTaxMinor()).append(',')
                    .append(major(r.netTaxMinor(), r.currency())).append('\n');
        }
        return sb.toString();
    }

    /** Minor units as a decimal amount using the currency's fraction digits (2 when unknown). */
    static String major(long minor, String currency) {
        int digits = 2;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException | NullPointerException ex) {
            digits = 2;
        }
        return BigDecimal.valueOf(minor, digits).toPlainString();
    }

    /** RFC 4180 quoting; also neutralises spreadsheet formula prefixes. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
