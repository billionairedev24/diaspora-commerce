package com.afrotransact.orders.application;

import java.time.Instant;

import com.afrotransact.orders.domain.ShipTo;

/**
 * One row of the seller's shipment queue.
 *
 * @param orderId the order's id, which payments reports against held funds (the screens show the order number)
 */
public record SellerShipmentView(String orderId, String orderNumber, String orderStatus, Instant placedAt, ShipTo shipTo,
                                 OrderView.ShipmentView shipment) {
}
