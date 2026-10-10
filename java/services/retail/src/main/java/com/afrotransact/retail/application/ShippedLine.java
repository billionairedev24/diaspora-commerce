package com.afrotransact.retail.application;

import java.util.List;

/** A shipped order line from {@code inventory.reservation.committed}, with the lots it was picked from. */
public record ShippedLine(String sku, int qty, List<LotCost> lots) {

    public ShippedLine {
        lots = lots == null ? List.of() : List.copyOf(lots);
    }

    /** Units picked from one lot at its landed cost. */
    public record LotCost(String lotNo, int qty, long unitLandedCostMinor, String currency) {
    }
}
