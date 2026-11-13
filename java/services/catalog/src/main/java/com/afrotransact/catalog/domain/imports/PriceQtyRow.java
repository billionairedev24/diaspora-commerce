package com.afrotransact.catalog.domain.imports;

/**
 * One row of the price and quantity feed (S394). Null means the cell was blank: leave it as it is. {@code listPriceMinor}
 * 0 removes the list price. The row names its offer by Seller SKU first, else by ATIN, else by barcode; {@code gtin} is
 * only set when neither of the other two is given, and offers-service accepts it only when it matches exactly one of the
 * seller's own listings.
 */
public record PriceQtyRow(int rowNumber, String sku, String atin, Long priceMinor, Long listPriceMinor, Integer quantity,
                          String gtin) {

    /** A row named by SKU or ATIN. */
    public PriceQtyRow(int rowNumber, String sku, String atin, Long priceMinor, Long listPriceMinor, Integer quantity) {
        this(rowNumber, sku, atin, priceMinor, listPriceMinor, quantity, null);
    }
}
