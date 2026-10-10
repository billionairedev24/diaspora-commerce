package com.afrotransact.payments.domain;

/** Who ships a line: AfroTransact fulfilment centres (FBA, also all 1P stock) or the seller itself. */
public enum Fulfilment {
    FBA,
    SELF
}
