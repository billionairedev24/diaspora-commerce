package com.afrotransact.retail.application;

import java.util.UUID;

/** A PO line as entered by the buyer; a null unit cost takes the supplier's agreed cost for the item. */
public record PoLineInput(UUID itemId, int qty, Long unitCostMinor) {
}
