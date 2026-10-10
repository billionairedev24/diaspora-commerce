package com.afrotransact.retail.domain;

/** Chart of accounts used by retail postings (design ACCOUNTS plus AP, price variance and FX). */
public enum GlAccount {
    INVENTORY("1300", "Inventory — finished goods"),
    IN_TRANSIT("1310", "Inventory in transit"),
    QUARANTINE("1320", "Inventory — quarantine"),
    ACCOUNTS_PAYABLE("2100", "Accounts payable — suppliers"),
    GR_IR("2110", "GR/IR clearing (supplier invoices)"),
    ACCRUED_LANDED_COST("2120", "Accrued freight, duty & brokerage"),
    COGS("5000", "Cost of goods sold"),
    PURCHASE_PRICE_VARIANCE("5010", "Purchase price variance"),
    SHRINK("5090", "Inventory shrink & write-offs"),
    FX_GAIN_LOSS("7100", "Realised FX gain/loss");

    private final String code;
    private final String label;

    GlAccount(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
