package com.afrotransact.retail.port;

import java.time.LocalDate;
import java.util.List;

/**
 * Lots of a completed goods receipt for inventory-service {@code POST /v1/receipts}. {@code ownerOrg} null means
 * AfroTransact Retail (1P).
 */
public record InventoryReceipt(String receiptRef, String nodeId, String ownerOrg, List<Line> lines) {

    public InventoryReceipt {
        lines = List.copyOf(lines);
    }

    /** One batch: good units become a lot, damaged units a separate -DMG quarantine lot. */
    public record Line(String sku, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood, int qtyDamaged,
                       String bin, long unitLandedCostMinor, String currency, Integer shelfLifeDays) {
    }
}
