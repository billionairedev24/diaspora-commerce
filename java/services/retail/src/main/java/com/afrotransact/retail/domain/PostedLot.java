package com.afrotransact.retail.domain;

import java.util.UUID;

/** A lot created in inventory by a completed receipt (sellable, quarantined or the -DMG lot). */
public record PostedLot(UUID lotId, String sku, String lotNo, int qty, String status, long unitLandedCostMinor) {
}
