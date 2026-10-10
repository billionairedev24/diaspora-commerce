package com.afrotransact.payments.domain;

import java.time.Instant;

/**
 * Net still on settlement hold for one order.
 *
 * @param releaseAt when it becomes available; null while the order is not delivered / completed yet
 */
public record HeldOrder(String orderId, Money amount, Instant releaseAt) {
}
