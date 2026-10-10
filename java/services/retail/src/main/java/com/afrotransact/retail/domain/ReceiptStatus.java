package com.afrotransact.retail.domain;

/** Goods receipt lifecycle: counted while OPEN, POSTING while lots are sent to inventory, then COMPLETED. */
public enum ReceiptStatus {
    OPEN,
    POSTING,
    COMPLETED
}
