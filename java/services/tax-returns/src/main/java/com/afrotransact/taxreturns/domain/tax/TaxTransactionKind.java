package com.afrotransact.taxreturns.domain.tax;

/** Tax ledger entry kind. */
public enum TaxTransactionKind {
    /** Tax collected on a placed order line (positive). */
    SALE,
    /** Tax given back by a refund (negative), booked in the month of the refund. */
    REFUND
}
