package com.afrotransact.taxreturns.domain.returns;

import java.util.List;

/** Refund math (S115). */
public interface RefundCalculator {

    /**
     * Refund for returned units: item price + proportional tax + the shipment's shipping when the seller is at fault,
     * minus a restocking fee on no-fault lines.
     *
     * @param charged           every charged line of the order (the shipping of a shipment may sit on another line)
     * @param selections        units to refund
     * @param restockingFeeBps  restocking fee for no-fault lines (basis points of the items amount)
     */
    RefundBreakdown forReturn(List<RefundableLine> charged, List<RefundSelection> selections, int restockingFeeBps);

    /** A partial (goodwill) refund of an amount of a line's items and shipping, with its proportional tax. */
    RefundLineAmount adjustment(RefundableLine line, long itemsMinor, long shippingMinor);
}
