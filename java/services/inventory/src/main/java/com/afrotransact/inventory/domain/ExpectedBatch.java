package com.afrotransact.inventory.domain;

import java.time.LocalDate;

/**
 * What the shipper announces about one inbound line (the ASN / packing list): units per case and the batch with its
 * manufacture and expiry dates (GS1 AIs 10, 11 and 17). Receiving confirms or corrects these at the dock; a line
 * received without its own lot and dates takes them from here.
 *
 * @param casePack units per case (null when units are not case-packed)
 * @param lotNo    supplier batch / lot number, or null
 * @param mfgDate  manufacture date, or null
 * @param expDate  expiry or best-before date, or null when the product does not expire
 */
public record ExpectedBatch(Integer casePack, String lotNo, LocalDate mfgDate, LocalDate expDate) {

    /** No case pack and no batch announced. */
    public static final ExpectedBatch NONE = new ExpectedBatch(null, null, null, null);

    public ExpectedBatch {
        if (casePack != null && (casePack < 1 || casePack > 10_000)) {
            throw new InventoryRuleViolation("inventory.invalid-quantity", "Case pack must be 1 to 10000 units");
        }
        if (mfgDate != null && expDate != null && !mfgDate.isBefore(expDate)) {
            throw new InventoryRuleViolation("inventory.invalid-dates", "Expiry " + expDate
                    + " must be after manufacture " + mfgDate);
        }
        lotNo = lotNo == null || lotNo.isBlank() ? null : lotNo.trim();
    }

    /** True when anything was announced. */
    public boolean present() {
        return casePack != null || lotNo != null || mfgDate != null || expDate != null;
    }

    /** Number of cases for {@code qty} units (0 when not case-packed). */
    public int cases(int qty) {
        return casePack == null ? 0 : (qty + casePack - 1) / casePack;
    }
}
