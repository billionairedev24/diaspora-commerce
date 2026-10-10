package com.afrotransact.taxreturns.application;

import java.util.Map;

import com.afrotransact.taxreturns.domain.tax.TaxCategory;

/** Catalog product type → tax category (unlisted types are {@link TaxCategory#GENERAL}). */
public record TaxSettings(Map<String, TaxCategory> categoryByProductType) {

    public TaxSettings {
        categoryByProductType = Map.copyOf(categoryByProductType);
    }

    public TaxCategory categoryFor(String productType) {
        return productType == null ? TaxCategory.GENERAL
                : categoryByProductType.getOrDefault(productType, TaxCategory.GENERAL);
    }
}
