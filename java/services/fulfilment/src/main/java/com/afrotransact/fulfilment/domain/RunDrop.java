package com.afrotransact.fulfilment.domain;

import java.util.List;

/**
 * One order's drop on a run: its shipments (a customer is visited once), the bags they hold and how far it is from
 * the hub by road (null when it could not be measured).
 *
 * @param orderNumber    customer order number
 * @param shipmentIds    the order's pre-order shipments
 * @param packages       bags the driver hands over at the drop
 * @param distanceMeters road distance from the hub, null when unknown
 */
public record RunDrop(String orderNumber, List<String> shipmentIds, int packages, Double distanceMeters) {

    public RunDrop {
        shipmentIds = List.copyOf(shipmentIds);
    }
}
