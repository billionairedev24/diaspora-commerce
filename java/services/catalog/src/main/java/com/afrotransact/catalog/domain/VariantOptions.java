package com.afrotransact.catalog.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.SizeChart;

/**
 * Brings the option values of a product's variants to one spelling before they are validated and stored: a value that
 * matches one of the category's options (ignoring case) takes the category's spelling, and the other custom values
 * of a dimension take the spelling of their first use on the product ("free size" after "Free size" is "Free size").
 */
public final class VariantOptions {

    private VariantOptions() {
    }

    /**
     * The variants with the size spelled as the size chart's row spells it ("free size" becomes "Free size" when that
     * is the chart's row); sizes the chart does not have are left as they are for the rules to report.
     *
     * @param sizeDimension the dimension that holds the size
     */
    public static List<VariantDraft> spelledLikeChart(String sizeDimension, SizeChart chart, List<VariantDraft> variants) {
        if (chart == null) {
            return variants;
        }
        List<VariantDraft> out = new ArrayList<>();
        for (VariantDraft v : variants) {
            String size = v.options().get(sizeDimension);
            Map<String, String> options = v.options();
            if (size != null) {
                options = new LinkedHashMap<>(options);
                options.put(sizeDimension, chart.row(size).map(ChartRow::size).orElse(size));
            }
            out.add(new VariantDraft(options, v.sellerSku(), v.gtin(), v.gtinExempt(), v.priceMinor(), v.quantity(),
                    v.compareAtMinor(), v.images(), v.atin(), v.taxFacts(), v.packaging()));
        }
        return out;
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
                    v.compareAtMinor(), v.images(), v.atin(), v.taxFacts(), v.packaging()));
        }
        return out;
    }
}
