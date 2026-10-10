package com.afrotransact.inventory.application;

import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;

/** Payload of {@code inventory.lot.*} events (finance posts GL entries from them; recalls trace them). */
public record LotEventPayload(UUID lotId, String sku, String lotNo, String nodeId, String ownerOrg, String bin,
                              int qtyOnHand, int qty, String status, LocalDate mfgDate, LocalDate expDate,
                              long unitLandedCostMinor, String currency, String reason, String receiptRef) {

    /** @param qty units affected by this event (for example units written off) */
    static LotEventPayload of(Lot lot, int qty) {
        return new LotEventPayload(lot.id(), lot.sku(), lot.lotNo(), lot.nodeId(), lot.ownerOrg(), lot.bin(),
                lot.qtyOnHand(), qty, lot.status().name(), lot.mfgDate(), lot.expDate(), lot.unitLandedCostMinor(),
                lot.currency(), lot.reason(), lot.receiptRef());
    }
}
