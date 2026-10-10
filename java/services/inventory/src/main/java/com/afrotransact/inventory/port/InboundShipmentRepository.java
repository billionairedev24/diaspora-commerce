package com.afrotransact.inventory.port;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.InboundShipment;
import com.afrotransact.inventory.domain.InboundSource;
import com.afrotransact.inventory.domain.InboundStatus;

/**
 * Inbound shipments with their lines and boxes. Writers lock the shipment ({@link #lockById}) before any stock
 * position, so the lock order is shipment → positions (sku, node) → lots everywhere.
 */
public interface InboundShipmentRepository {

    /** Next FBA plan number, e.g. {@code FBA-IN-006600}. */
    String nextFbaNumber();

    void insert(InboundShipment shipment);

    /** Stores the shipment, lines and boxes if its version still matches (409 otherwise). */
    InboundShipment update(InboundShipment shipment);

    Optional<InboundShipment> findById(UUID id);

    Optional<InboundShipment> lockById(UUID id);

    /** By plan number or, for retail ASNs, by the retail shipment reference; locked. */
    Optional<InboundShipment> lockByReference(String reference);

    Optional<InboundShipment> lockByExternalRef(InboundSource source, String externalRef);

    Page<InboundShipment> search(InboundQuery query);

    /** Filter of inbound shipment lists; null fields are not filtered. */
    record InboundQuery(String ownerOrg, String nodeId, InboundStatus status, InboundSource source, String sku,
                        int page, int size) {
    }
}
