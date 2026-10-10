package com.afrotransact.orders.port;

import java.time.Instant;

/** Live shipment status feed for the staff console and seller center (server-sent events). */
public interface ShipmentFeed {

    /** Called after the transition committed. */
    void publish(Update update);

    /** One status change as pushed to subscribers. */
    record Update(String orderNumber, String shipmentId, String sellerOrgId, String from, String to, String actor,
                  String source, Instant at) {
    }
}
