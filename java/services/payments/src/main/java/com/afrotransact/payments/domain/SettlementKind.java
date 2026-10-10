package com.afrotransact.payments.domain;

/** What starts the settlement hold: product delivery or service (booking) completion. */
public enum SettlementKind {
    PRODUCT,
    SERVICE
}
