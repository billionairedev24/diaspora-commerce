package com.afrotransact.seller.domain;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * The category and its ancestors, as the catalog sends them (comma-separated: a listing sits in a product type
     * inside a category inside a department, and a rule may be about any of them).
     */
    public List<String> categoryCodes() {
        if (categoryCode == null) {
            return List.of();
        }
        return Arrays.stream(categoryCode.split(",")).map(String::strip).filter(c -> !c.isEmpty()).distinct().toList();
    }

    /** The scopes whose rules can apply to this listing. */
    public List<SellingScope> candidateScopes() {
        List<SellingScope> out = new ArrayList<>();
        categoryCodes().forEach(c -> out.add(SellingScope.category(c)));
        if (productTypeCode != null) {
            out.add(SellingScope.productType(productTypeCode));
        }
        if (brand != null && !SellingScope.normaliseBrand(brand).isEmpty()) {
            out.add(SellingScope.brand(brand));
            categoryCodes().forEach(c -> out.add(SellingScope.brandInCategory(brand, c)));
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
