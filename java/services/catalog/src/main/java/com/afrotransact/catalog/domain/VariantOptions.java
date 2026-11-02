package com.afrotransact.catalog.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings the option values of a product's variants to one spelling before they are validated and stored: a value that
 * matches one of the category's options (ignoring case) takes the category's spelling, and the other custom values
 * of a dimension take the spelling of their first use on the product ("free size" after "Free size" is "Free size").
 */
public final class VariantOptions {

    private VariantOptions() {
    }

    /** The variants with canonical option values; dimensions that do not allow custom values are left as typed. */
    public static List<VariantDraft> canonical(ProductType type, String themeName, List<VariantDraft> variants) {
        VariationTheme theme = themeName == null || themeName.isBlank() ? null : type.theme(themeName.strip()).orElse(null);
        if (theme == null) {
            return variants;
        }
        Map<String, Map<String, String>> firstSpelling = new HashMap<>();
        List<VariantDraft> out = new ArrayList<>();
        for (VariantDraft v : variants) {
            Map<String, String> options = new LinkedHashMap<>(v.options());
            for (VariationDimension dim : theme.dimensions()) {
                String raw = options.get(dim.name());
                if (raw == null || !dim.allowCustomValues()) {
                    continue;
                }
                String cleaned = CustomValues.clean(raw);
                String value = dim.suggestion(cleaned).orElseGet(() -> firstSpelling
                        .computeIfAbsent(dim.name(), k -> new HashMap<>())
                        .computeIfAbsent(CustomValues.key(cleaned), k -> cleaned));
                options.put(dim.name(), value);
            }
            out.add(new VariantDraft(options, v.sellerSku(), v.gtin(), v.gtinExempt(), v.priceMinor(), v.quantity(),
                    v.compareAtMinor(), v.images()));
        }
        return out;
    }
}
