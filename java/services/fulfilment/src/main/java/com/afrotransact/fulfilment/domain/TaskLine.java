package com.afrotransact.fulfilment.domain;

/**
 * One line to pick.
 *
 * @param lineId      orders-service line id
 * @param variantAtin variant ATIN (inventory SKU)
 * @param title       product title
 * @param quantity    units ordered
 * @param picked      units picked so far
 * @param lotIds      lots picked from (FEFO allocation from inventory, or scanned)
 */
public record TaskLine(String lineId, String variantAtin, String title, int quantity, int picked, String lotIds) {
}
