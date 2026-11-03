package com.afrotransact.retail.application;

import java.util.List;

/** A shipped order line from {@code inventory.reservation.committed}, with the lots it was picked from. */
public record ShippedLine(String sku, int qty, List<LotCost> lots) {

    public ShippedLine {
        lots = lots == null ? List.of() : List.copyOf(lots);
    }

    /**
     * Units picked from one lot at its landed cost: in the currency the lot was paid in and, for COGS, in US cents.
     *
     * @param unitCostUsdMinor the lot's unit cost in US cents, fixed when inventory received it; null when the event
     *                         predates exchange rates and the lot was not in US dollars
     */
    public record LotCost(String lotNo, int qty, long unitLandedCostMinor, String currency, Long unitCostUsdMinor) {

        /** A lot costed in the currency given; its US-dollar value is the amount itself when that is USD. */
        public LotCost(String lotNo, int qty, long unitLandedCostMinor, String currency) {
            this(lotNo, qty, unitLandedCostMinor, currency, "USD".equals(currency) ? unitLandedCostMinor : null);
        }
    }
}
