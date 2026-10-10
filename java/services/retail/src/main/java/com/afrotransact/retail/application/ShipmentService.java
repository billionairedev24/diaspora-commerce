package com.afrotransact.retail.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.Carton;
import com.afrotransact.retail.domain.DocumentStatus;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.LandedCost;
import com.afrotransact.retail.domain.ShipmentStage;
import com.afrotransact.retail.port.Page;

/** Inbound shipments, advance ship notices, customs documents and milestones (S071). */
public interface ShipmentService {

    InboundShipment book(BookShipmentCommand command);

    /** Replaces the ASN lines and cartons; locked after departure. */
    InboundShipment updateAsn(UUID id, List<AsnLineInput> lines, List<Carton> cartons, Long expectedVersion);

    InboundShipment setDocument(UUID id, String name, DocumentStatus status, String reference, String note);

    InboundShipment updateCharges(UUID id, List<LandedCharge> charges, Long expectedVersion);

    /** Next milestone; departure posts Dr 1310 / Cr 2110 for the FOB value. */
    InboundShipment advance(UUID id, Long expectedVersion);

    /** Estimated landed cost of the ASN (FOB at the PO rate + duty + charges). */
    LandedCost landedCostEstimate(UUID id);

    InboundShipment get(UUID id);

    Page<InboundShipment> list(ShipmentStage stage, UUID poId, int page, int size);
}
