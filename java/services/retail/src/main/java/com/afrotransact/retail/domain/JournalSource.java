package com.afrotransact.retail.domain;

/** Workflow step that produced a journal entry. */
public enum JournalSource {
    SHIPMENT_DEPARTED,
    GOODS_RECEIPT,
    INVOICE_MATCH,
    COGS
}
