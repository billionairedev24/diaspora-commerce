package com.afrotransact.deliveryruns.domain;

/**
 * One product line of a pre-order.
 *
 * @param atin        variant ATIN
 * @param name        product title
 * @param sellerOrgId seller organisation (picking list)
 * @param sellerName  seller display name (null when unknown)
 * @param qty         quantity
 */
public record PreorderLine(String atin, String name, String sellerOrgId, String sellerName, int qty) {

    public PreorderLine {
        if (qty < 0) {
            throw new RuleViolation("delivery-runs.invalid-quantity", "Quantities cannot be negative");
        }
        name = name == null || name.isBlank() ? (atin == null ? "" : atin) : name;
    }

    public PreorderLine(String atin, String name, String sellerOrgId, int qty) {
        this(atin, name, sellerOrgId, null, qty);
    }

    /** Seller name, else the organisation id. */
    public String seller() {
        return sellerName != null && !sellerName.isBlank() ? sellerName : sellerOrgId;
    }
}
