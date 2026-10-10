package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Units of one lot allocated to a reservation line. Cost is captured for COGS at shipment. */
public record LotAllocation(UUID lotId, String lotNo, int qty, LocalDate expDate, long unitLandedCostMinor,
                           String currency) {

    public long costMinor() {
        return unitLandedCostMinor * qty;
    }
}
