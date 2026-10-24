package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Units of one lot allocated to a reservation line. Cost is captured for COGS at shipment: in the currency the lot was
 * paid in and in US dollars (what COGS and margin use).
 *
 * @param unitCostUsdMinor unit landed cost of the lot in US cents
 */
public record LotAllocation(UUID lotId, String lotNo, int qty, LocalDate expDate, long unitLandedCostMinor,
                           String currency, long unitCostUsdMinor) {

    /** Cost of the allocated units in the lot's own currency. */
    public long costMinor() {
        return unitLandedCostMinor * qty;
    }

    /** Cost of the allocated units in US cents. */
    public long costUsdMinor() {
        return unitCostUsdMinor * qty;
    }
}
