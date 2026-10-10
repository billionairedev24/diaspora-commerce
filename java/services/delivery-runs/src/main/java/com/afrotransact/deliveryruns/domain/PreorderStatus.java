package com.afrotransact.deliveryruns.domain;

/**
 * Derived status of a pre-order window (a pure function of the window and now): {@code DRAFT} if draft, else
 * {@code PAUSED} if not active, else {@code UPCOMING} before it opens, {@code OPEN} until it closes (inclusive),
 * {@code CLOSED} until the day after delivery, then {@code DELIVERED}.
 */
public enum PreorderStatus {
    DRAFT,
    PAUSED,
    UPCOMING,
    OPEN,
    CLOSED,
    DELIVERED
}
