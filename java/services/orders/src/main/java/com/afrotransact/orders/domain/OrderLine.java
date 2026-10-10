package com.afrotransact.orders.domain;

import java.util.Objects;

/**
 * One ordered variant with its price snapshot. Money is in minor units of the order currency.
 *
 * @param id              line id (also the payments line id)
 * @param lineNo          1-based position in the order
 * @param variantAtin     variant ATIN (inventory SKU for AfroTransact-fulfilled lines)
 * @param productAtin     product ATIN
 * @param title           title snapshot
 * @param imageUrl        image snapshot
 * @param quantity        units
 * @param unitPriceMinor  unit price paid
 * @param taxMinor        sales tax of the line
 * @param discountMinor   discount applied to the line
 * @param category        commission category sent to payments
 * @param perishable      frozen / chilled food (no safe-place delivery, handover code required)
 * @param taxJurisdiction e.g. US-TX
 */
public record OrderLine(String id, int lineNo, String variantAtin, String productAtin, String title, String imageUrl,
                        int quantity, long unitPriceMinor, long taxMinor, long discountMinor, String category,
                        boolean perishable, String taxJurisdiction) {

    public OrderLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(variantAtin, "variantAtin");
        if (quantity <= 0) {
            throw new OrdersRuleViolation("orders.invalid-quantity", "Line " + lineNo + " needs a quantity >= 1");
        }
        if (unitPriceMinor < 0 || taxMinor < 0 || discountMinor < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "Line amounts cannot be negative");
        }
    }

    /** Unit price × quantity. */
    public long itemsMinor() {
        return Math.multiplyExact(unitPriceMinor, (long) quantity);
    }

    /** Items + tax − discount. */
    public long totalMinor() {
        return itemsMinor() + taxMinor - discountMinor;
    }
}
