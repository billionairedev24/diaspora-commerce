package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Receipt-driven supplier performance: on-time % (receipts completed by the ETA) and defect % (damaged or QA-failed
 * units over units received). Updated by every completed goods receipt.
 */
public record SupplierScorecard(int receipts, int onTimeReceipts, long unitsReceived, long unitsDefective) {

    public static final SupplierScorecard EMPTY = new SupplierScorecard(0, 0, 0, 0);

    public SupplierScorecard {
        if (receipts < 0 || onTimeReceipts < 0 || onTimeReceipts > receipts || unitsReceived < 0
                || unitsDefective < 0 || unitsDefective > unitsReceived) {
            throw new IllegalArgumentException("Inconsistent scorecard");
        }
    }

    public SupplierScorecard record(boolean onTime, long units, long defective) {
        return new SupplierScorecard(receipts + 1, onTimeReceipts + (onTime ? 1 : 0), unitsReceived + units,
                unitsDefective + Math.min(defective, units));
    }

    /** On-time percentage with one decimal; null until the first receipt. */
    public BigDecimal onTimePercent() {
        return receipts == 0 ? null : percent(onTimeReceipts, receipts);
    }

    /** Defect percentage with one decimal; null until units are received. */
    public BigDecimal defectPercent() {
        return unitsReceived == 0 ? null : percent(unitsDefective, unitsReceived);
    }

    private static BigDecimal percent(long part, long whole) {
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
