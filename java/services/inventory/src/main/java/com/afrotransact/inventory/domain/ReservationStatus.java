package com.afrotransact.inventory.domain;

/** HELD at checkout → COMMITTED on shipment, or RELEASED (cancel / payment failure) / EXPIRED (TTL). */
public enum ReservationStatus {
    HELD,
    COMMITTED,
    RELEASED,
    EXPIRED
}
