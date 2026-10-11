package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One lot in a cycle count.
 *
 * @param expectedQty units on hand when the count started (the system quantity)
 * @param countedQty  units counted, or null until counted
 * @param reasonCode  required when the counted quantity differs from the expected one
 * @param postedDelta units added (positive) or removed (negative) when the count was posted, else null
 */
public record CycleCountLine(int lineNo, UUID lotId, String sku, String lotNo, String bin, LocalDate expDate,
                             int expectedQty, Integer countedQty, CycleCountReason reasonCode, String note,
                             String countedBy, Instant countedAt, Integer postedDelta) {

    public CycleCountLine {
        if (expectedQty < 0 || (countedQty != null && countedQty < 0)) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Counted quantities cannot be negative");
        }
    }

    public boolean counted() {
        return countedQty != null;
    }

    /** Counted − expected (0 until counted). */
    public int variance() {
        return countedQty == null ? 0 : countedQty - expectedQty;
    }

    public CycleCountLine count(int qty, CycleCountReason reason, String why, String actor, Instant now) {
        if (qty < 0 || qty > 1_000_000) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Count 0 to 1000000 units");
        }
        return new CycleCountLine(lineNo, lotId, sku, lotNo, bin, expDate, expectedQty, qty, reason, why, actor, now,
                null);
    }

    public CycleCountLine posted(int delta) {
        return new CycleCountLine(lineNo, lotId, sku, lotNo, bin, expDate, expectedQty, countedQty, reasonCode, note,
                countedBy, countedAt, delta);
    }
}
