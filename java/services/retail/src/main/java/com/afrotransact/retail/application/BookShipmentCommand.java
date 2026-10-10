package com.afrotransact.retail.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.TransportMode;

/**
 * Book an inbound shipment for a PO. Without {@code lines} the ASN ships every open PO quantity; batch details come
 * from the supplier's packing list later.
 */
public record BookShipmentCommand(UUID poId, TransportMode mode, String carrier, String trackingRef,
                                  String billOfLading, String originPort, String destinationPort, LocalDate etd,
                                  LocalDate eta, List<AsnLineInput> lines, List<LandedCharge> charges) {
}
