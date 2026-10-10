package com.afrotransact.retail.domain;

/** Per-line result of the three-way match. */
public enum MatchLineStatus {
    MATCH,
    PRICE_VARIANCE,
    AMOUNT_VARIANCE,
    QTY_OVER_ORDERED,
    QTY_OVER_RECEIVED,
    AWAITING_RECEIPT,
    NOT_ON_PO
}
