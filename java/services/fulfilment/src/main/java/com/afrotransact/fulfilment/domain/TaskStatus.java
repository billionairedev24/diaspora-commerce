package com.afrotransact.fulfilment.domain;

/** Fulfilment-centre task for one FBA / Retail 1P shipment. */
public enum TaskStatus {
    PICK_PENDING,
    PICKING,
    /** Picked short: some units were not found; ops resolve (recount, re-reserve or cancel). */
    PICK_EXCEPTION,
    PICKED,
    PACKED,
    /** Staged at the outbound dock: handed to a carrier or waiting for a driver route. */
    READY,
    CANCELLED
}
