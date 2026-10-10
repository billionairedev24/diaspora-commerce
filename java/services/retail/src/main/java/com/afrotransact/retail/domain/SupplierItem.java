package com.afrotransact.retail.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * How one supplier sells an item: their SKU, case pack, minimum order and last agreed unit cost in their currency.
 */
public record SupplierItem(UUID supplierId, String supplierSku, int casePack, int minOrderQty, long unitCostMinor,
                           String currency) {

    public SupplierItem {
        Objects.requireNonNull(supplierId, "supplierId");
        if (supplierSku == null || supplierSku.isBlank() || supplierSku.length() > 60) {
            throw new RetailRuleViolation("retail.item-invalid", "Supplier SKU is required (max 60 characters)");
        }
        if (casePack < 1) {
            throw new RetailRuleViolation("retail.item-invalid", "Supplier case pack must be at least 1");
        }
        if (minOrderQty < 0) {
            throw new RetailRuleViolation("retail.item-invalid", "Minimum order quantity cannot be negative");
        }
        if (unitCostMinor < 0) {
            throw new RetailRuleViolation("retail.item-invalid", "Supplier unit cost cannot be negative");
        }
        currency = Currencies.require(currency);
    }
}
