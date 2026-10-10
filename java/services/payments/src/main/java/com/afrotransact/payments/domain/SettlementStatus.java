package com.afrotransact.payments.domain;

/** OPEN while some seller payable of the order may still need a transfer. */
public enum SettlementStatus {
    OPEN,
    SETTLED
}
