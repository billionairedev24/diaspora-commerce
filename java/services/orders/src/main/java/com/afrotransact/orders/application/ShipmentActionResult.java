package com.afrotransact.orders.application;

/**
 * Result of a shipment action.
 *
 * @param outcome applied | unchanged (already in that status) | ignored (out-of-order event) | replayed (same event
 *                id seen before)
 * @param note    why it was ignored
 * @param order   the order as the caller may see it afterwards
 */
public record ShipmentActionResult(String outcome, String note, OrderView order) {
}
