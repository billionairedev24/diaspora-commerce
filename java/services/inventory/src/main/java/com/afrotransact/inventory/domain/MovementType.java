package com.afrotransact.inventory.domain;

/** Types of rows in the append-only stock ledger. */
public enum MovementType {
    RECEIVE,
    PUT_AWAY,
    RESERVE,
    RELEASE,
    SHIP,
    RETURN_RESTOCK,
    RETURN_DAMAGED,
    ADJUST,
    QUARANTINE,
    RELEASE_QUARANTINE,
    EXPIRE,
    WRITE_OFF,
    TRANSFER,
    INBOUND
}
