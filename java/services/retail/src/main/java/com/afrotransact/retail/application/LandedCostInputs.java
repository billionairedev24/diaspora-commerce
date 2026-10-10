package com.afrotransact.retail.application;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.LandedCostInput;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;

/** Builds landed-cost inputs: FOB = units × PO unit price, converted to base currency at the PO-date rate. */
final class LandedCostInputs {

    private LandedCostInputs() {
    }

    static LandedCostInput of(int lineNo, PurchaseOrder po, int poLineNo, int units, Map<UUID, RetailItem> items) {
        PoLine poLine = po.line(poLineNo);
        RetailItem item = items.get(poLine.itemId());
        long fob = po.fx().toBase(Math.multiplyExact((long) units, poLine.unitCostMinor()));
        return new LandedCostInput(lineNo, poLine.sku(), units, fob, item == null ? 0 : item.unitWeightGrams(),
                item == null ? null : item.dutyRatePercent());
    }
}
