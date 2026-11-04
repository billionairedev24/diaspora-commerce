package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.inventory.domain.Lot;

/**
 * Payload of {@code inventory.lot.*} events (finance posts GL entries from them; recalls trace them). The unit cost is
 * in the currency paid and in US cents; finance books the USD value.
 *
 * @param unitCostUsdMinor unit landed cost in US cents, fixed at receiving
 * @param fxRateToUsd      US dollars per 1 unit of {@code currency} used for it
 */
public record LotEventPayload(UUID lotId, String sku, String lotNo, String nodeId, String ownerOrg, String bin,
                              int qtyOnHand, int qty, String status, LocalDate mfgDate, LocalDate expDate,
                              long unitLandedCostMinor, String currency, long unitCostUsdMinor,
                              BigDecimal fxRateToUsd, String reason, String receiptRef) {

    /** @param qty units affected by this event (for example units written off) */
    static LotEventPayload of(Lot lot, int qty) {
        return new LotEventPayload(lot.id(), lot.sku(), lot.lotNo(), lot.nodeId(), lot.ownerOrg(), lot.bin(),
                lot.qtyOnHand(), qty, lot.status().name(), lot.mfgDate(), lot.expDate(), lot.unitLandedCostMinor(),
                lot.currency(), lot.unitCostUsdMinor(), lot.cost().rateToUsd(), lot.reason(), lot.receiptRef());
    }
}
