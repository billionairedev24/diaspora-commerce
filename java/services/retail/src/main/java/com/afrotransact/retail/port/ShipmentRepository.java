package com.afrotransact.retail.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.ShipmentStage;

/** Persistence port for inbound shipments and their ASNs. */
public interface ShipmentRepository {

    InboundShipment insert(InboundShipment shipment);

    InboundShipment update(InboundShipment shipment);

    Optional<InboundShipment> findById(UUID id);

    Optional<InboundShipment> findByReference(String reference);

    List<InboundShipment> findByPo(UUID poId);

    Page<InboundShipment> page(ShipmentStage stage, UUID poId, int page, int size);
}
