package com.afrotransact.orders.application;

import java.time.Instant;

import com.afrotransact.orders.domain.ShipTo;

/** One row of the seller's shipment queue. */
public record SellerShipmentView(String orderNumber, String orderStatus, Instant placedAt, ShipTo shipTo,
                                 OrderView.ShipmentView shipment) {
}
