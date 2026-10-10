package com.afrotransact.taxreturns.domain.returns;

/** Inventory posting of a returned line (only AfroTransact-fulfilled stock is tracked). */
public enum StockStatus {
    NOT_TRACKED,
    PENDING,
    POSTED,
    FAILED
}
