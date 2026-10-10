package com.afrotransact.inventory.domain;

/** Where an inbound shipment comes from. */
public enum InboundSource {
    /** A seller's "Send stock to AfroTransact" plan (FBA, S257): units labelled per seller with AT-FN codes. */
    FBA,
    /** An AfroTransact Retail purchase-order shipment (retail-service ASN), from its departure (S226). */
    RETAIL_ASN
}
