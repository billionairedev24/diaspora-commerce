package com.afrotransact.orders.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Test fixtures for shipments and orders. */
final class Shipments {

    static final HandoverPolicy HANDOVER = new DefaultHandoverPolicy();

    private Shipments() {
    }

    static OrderLine line(int lineNo, long unitPrice, int qty, long tax) {
        return new OrderLine(UUID.randomUUID().toString(), lineNo, "ATIN-" + lineNo, "P-" + lineNo, "Item " + lineNo,
                null, qty, unitPrice, tax, 0, "groceries", false, "US-TX");
    }

    static Shipment of(FulfilmentType type, DeliveryMethod method, ShipmentStatus status) {
        return build(type, method, status, false, DeliveryPreferences.defaults(), null);
    }

    static Shipment build(FulfilmentType type, DeliveryMethod method, ShipmentStatus status, boolean perishable,
                          DeliveryPreferences prefs, String code) {
        String id = UUID.randomUUID().toString();
        boolean required = code != null;
        return new Shipment(id, UUID.randomUUID().toString(), 1, "org_seller", "Mama Tee's Pantry", type,
                type.inventoryTracked() ? "HOU-FC" : null, method, "standard",
                method == DeliveryMethod.PICKUP ? "PICKUP-1" : null, prefs, perishable, required,
                required ? HANDOVER.hash(id, code) : null, null, status, 599, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), null, null, null, null, Instant.now(), List.of(line(1, 1250, 2, 103)), 0,
                null, type == FulfilmentType.PREORDER ? PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7",
                LocalDate.now().plusDays(3), Instant.now().plusSeconds(86_400), PreorderFulfilment.DOOR) : null);
    }
}
