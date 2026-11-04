package com.afrotransact.deliveryruns.domain;

/**
 * Derived status of a pre-order window (a pure function of the window and now): {@code DRAFT} if draft, else
 * {@code PAUSED} if not active, else {@code UPCOMING} before it opens, {@code OPEN} until it closes (inclusive),
 * {@code CLOSED} until the day after delivery, then {@code DELIVERED}. A window's outcome overrides the dates:
 * {@code AWAITING_DECISION} when it closed below its minimum and staff have not decided yet, {@code CANCELLED} once
 * it was cancelled.
 */
public enum PreorderStatus {
    DRAFT,
    PAUSED,
    UPCOMING,
    OPEN,
    CLOSED,
    DELIVERED,
    AWAITING_DECISION,
    CANCELLED
}
