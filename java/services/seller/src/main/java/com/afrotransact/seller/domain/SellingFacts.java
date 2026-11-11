package com.afrotransact.seller.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the seller has told us about a listing so far. Any part may be null while the form is being filled.
 *
 * @param material the product material (gold, silver, a gemstone, steel...); {@code watch} says it is a watch
 */
public record SellingFacts(String categoryCode, String brand, String productTypeCode, String material, String gtin,
                           boolean watch) {

    public SellingFacts {
        categoryCode = blank(categoryCode);
        brand = blank(brand);
        productTypeCode = blank(productTypeCode);
        material = blank(material);
        gtin = blank(gtin) == null ? null : gtin.replaceAll("\\D", "");
        watch = watch || isWatch(productTypeCode, material);
    }

    private static boolean isWatch(String productType, String material) {
        return productType != null && productType.toLowerCase(Locale.ROOT).contains("watch")
                || material != null && material.equalsIgnoreCase("watch");
    }

    /** The scopes whose rules can apply to this listing. */
    public List<SellingScope> candidateScopes() {
        List<SellingScope> out = new ArrayList<>();
        if (categoryCode != null) {
            out.add(SellingScope.category(categoryCode));
        }
        if (productTypeCode != null) {
            out.add(SellingScope.productType(productTypeCode));
        }
        if (brand != null && !SellingScope.normaliseBrand(brand).isEmpty()) {
            out.add(SellingScope.brand(brand));
            if (categoryCode != null) {
                out.add(SellingScope.brandInCategory(brand, categoryCode));
            }
        }
        if (gtin != null && !gtin.isEmpty()) {
            out.add(SellingScope.product(gtin));
        }
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
