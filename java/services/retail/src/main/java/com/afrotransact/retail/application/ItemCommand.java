package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.StorageType;
import com.afrotransact.retail.domain.SupplierItem;

/**
 * Product master input (S068). {@code atin} is optional on create (validated against catalog) and ignored on update;
 * {@code originCountry} defaults to the primary supplier's country. Shelf life in days.
 */
public record ItemCommand(String name, String category, String atin, UUID primarySupplierId,
                          List<SupplierItem> supplierItems, String originCountry, String hsCode,
                          BigDecimal dutyRatePercent, int shelfLifeDays, StorageType storage, int reorderPoint,
                          String packSize, int innerPack, int casePack, int unitWeightGrams, long standardCostMinor,
                          long priceMinor) {
}
