package com.afrotransact.taxreturns.domain.tax;

/**
 * One line to tax. Amounts are minor units of the quote currency.
 *
 * @param lineId         caller's line id (cart line or order line)
 * @param atin           variant ATIN
 * @param sellerOrgId    seller the tax is attributed to
 * @param category       product tax category
 * @param quantity       units
 * @param unitPriceMinor unit price
 * @param discountMinor  discount on the whole line (reduces the taxable amount)
 * @param shippingMinor  delivery charge allocated to this line (taxed only where shipping is taxable)
 * @param taxCode        our tax code (null when the caller gave only a category)
 * @param providerTaxCode the external provider's code for it, or null
 */
public record TaxLineInput(String lineId, String atin, String sellerOrgId, TaxCategory category, int quantity,
                           long unitPriceMinor, long discountMinor, long shippingMinor, String taxCode,
                           String providerTaxCode) {

    public TaxLineInput(String lineId, String atin, String sellerOrgId, TaxCategory category, int quantity,
                        long unitPriceMinor, long discountMinor, long shippingMinor) {
        this(lineId, atin, sellerOrgId, category, quantity, unitPriceMinor, discountMinor, shippingMinor, null, null);
    }

    public TaxLineInput {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPriceMinor < 0 || discountMinor < 0 || shippingMinor < 0) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        category = category == null ? TaxCategory.GENERAL : category;
    }

    /** Price × quantity − discount, never below zero. */
    public long taxableItemsMinor() {
        return Math.max(0L, Math.subtractExact(Math.multiplyExact(unitPriceMinor, (long) quantity), discountMinor));
    }
}
