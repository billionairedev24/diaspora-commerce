package com.afrotransact.catalog.domain.imports;

/**
 * One row of the price and quantity feed (S394). Null means the cell was blank: leave it as it is. {@code listPriceMinor}
 * 0 removes the list price.
 */
public record PriceQtyRow(int rowNumber, String sku, String atin, Long priceMinor, Long listPriceMinor, Integer quantity) {
}
