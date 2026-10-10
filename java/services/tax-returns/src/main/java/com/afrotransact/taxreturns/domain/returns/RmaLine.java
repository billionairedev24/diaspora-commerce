package com.afrotransact.taxreturns.domain.returns;

import java.util.List;

/**
 * A returned order line.
 *
 * @param lineId           order line id
 * @param atin             variant ATIN (inventory SKU)
 * @param title            title snapshot
 * @param quantity         units returned
 * @param unitPriceMinor   unit price paid
 * @param reason           return reason
 * @param comment          customer's comment
 * @param evidenceAssetIds media asset ids of the evidence photos
 * @param outcome          inspection outcome (null until inspected)
 * @param condition        inventory condition (null until inspected)
 * @param stockStatus      inventory posting
 * @param inspectionNote   inspector's note
 */
public record RmaLine(String lineId, String atin, String title, int quantity, long unitPriceMinor, ReturnReason reason,
                      String comment, List<String> evidenceAssetIds, InspectionOutcome outcome,
                      ItemCondition condition, StockStatus stockStatus, String inspectionNote) {

    public RmaLine {
        evidenceAssetIds = evidenceAssetIds == null ? List.of() : List.copyOf(evidenceAssetIds);
    }

    public RmaLine inspected(InspectionOutcome result, ItemCondition cond, StockStatus stock, String note) {
        return new RmaLine(lineId, atin, title, quantity, unitPriceMinor, reason, comment, evidenceAssetIds, result,
                cond, stock, note);
    }

    public RmaLine withStock(StockStatus stock) {
        return new RmaLine(lineId, atin, title, quantity, unitPriceMinor, reason, comment, evidenceAssetIds, outcome,
                condition, stock, inspectionNote);
    }
}
