package com.afrotransact.retail.domain;

import java.util.List;

/** Inbound transport mode and the documents each mode needs (design createShipment). */
public enum TransportMode {
    OCEAN_FCL,
    OCEAN_LCL,
    AIR,
    ROAD;

    public List<String> requiredDocuments() {
        return switch (this) {
            case AIR -> List.of("Commercial invoice", "Packing list", "Air waybill", "Certificate of origin",
                    "FDA prior notice", "CBP entry (7501)");
            case OCEAN_FCL, OCEAN_LCL -> List.of("Commercial invoice", "Packing list", "Bill of lading",
                    "Certificate of origin", "ISF 10+2 filed", "FDA prior notice", "CBP entry (7501)");
            case ROAD -> List.of("Commercial invoice", "Packing list", "Certificate of origin", "CBP entry (7501)");
        };
    }
}
