package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Part of a plan fee settled with one order's transfer (S298): {@code amount} positive when recovered from it (the
 * transfer was smaller), negative when a released fee was paid back with it.
 */
public record PlanFeeDeduction(UUID withholdingId, String orderId, Money amount, Instant at) {
}
