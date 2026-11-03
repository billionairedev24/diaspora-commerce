package com.afrotransact.catalog.domain.sizing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.CustomValues;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.VariationTheme;

/** The size chart rules of a listing; see {@link SizeChartPolicy}. */
public class DefaultSizeChartPolicy implements SizeChartPolicy {

    public static final String FIELD = "sizeChartId";

    @Override
    public Map<String, String> problems(SizedListing l) {
        Map<String, String> errors = new LinkedHashMap<>();
        Optional<SizingTrait> sizing = l.type().sizing();
        if (sizing.isEmpty()) {
            if (l.chartId() != null && !l.chartId().equals(l.linkedChartId())) {
                errors.put(FIELD, "Size charts are not used for " + l.type().label());
            }
            return errors;
        }
        if (l.chartId() == null) {
            if (sizing.get().sizeChartRequired()) {
                errors.put(FIELD, "Add a size chart: shoppers need it to choose a size for " + l.type().label());
            }
            return errors;
        }
        SizeChart chart = l.chart();
        if (chart == null || !chart.ownerOrg().equals(l.ownerOrg())) {
            errors.put(FIELD, "Size chart not found");
            return errors;
        }
        if (chart.retired() && !chart.id().equals(l.linkedChartId())) {
            errors.put(FIELD, "The size chart \"" + chart.name() + "\" was retired; choose another or create a new one");
            return errors;
        }
        coverage(l, sizing.get(), chart, errors);
        return errors;
    }

    /** Every size the variants sell has a row in the chart. */
    private static void coverage(SizedListing l, SizingTrait sizing, SizeChart chart, Map<String, String> errors) {
        VariationTheme theme = l.variationTheme().isEmpty() ? null : l.type().theme(l.variationTheme()).orElse(null);
        if (theme == null || theme.dimension(sizing.sizeDimension()) == null) {
            return;
        }
        List<VariantDraft> variants = l.variants();
        for (int i = 0; i < variants.size(); i++) {
            String size = variants.get(i).options().get(sizing.sizeDimension());
            if (size == null || size.isBlank()) {
                continue;
            }
            if (chart.row(size).isEmpty()) {
                errors.put("variants[" + i + "].options." + sizing.sizeDimension(),
                        sizing.sizeDimension() + " \"" + CustomValues.clean(size) + "\" has no row in the size chart \""
                                + chart.name() + "\". Add it to the chart or choose another chart.");
            }
        }
    }
}
