package com.afrotransact.fulfilment.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;

/** AfroTransact Delivery shipments known to dispatch. */
public interface DeliveryShipmentRepository {

    /** @return true when inserted, false when the shipment is already known */
    boolean insert(DeliveryShipment shipment);

    Optional<DeliveryShipment> find(String shipmentId);

    /** Locks the given shipments (for routing). */
    List<DeliveryShipment> lockAll(List<String> shipmentIds);

    List<DeliveryShipment> awaitingRoute(String nodeId, int limit);

    void updateStatus(String shipmentId, DeliveryShipmentStatus status, String routeId);

    void updateLabel(String shipmentId, String label);
}
