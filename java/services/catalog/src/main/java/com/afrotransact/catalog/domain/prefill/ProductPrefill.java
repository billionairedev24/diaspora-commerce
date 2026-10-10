package com.afrotransact.catalog.domain.prefill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Product-builder prefill merged from providers in lookup order: each field comes from the first provider that has
 * it, and {@code importedFrom} says which one, so the form can mark imported fields (they stay editable).
 *
 * @param importedFrom field name ({@code title}, {@code brand}, {@code netContent}, {@code description},
 *                     {@code imageUrls}) → provider
 */
public record ProductPrefill(String title, String brand, String netContent, String description,
                             List<String> imageUrls, List<String> sourceCategories, Map<String, String> importedFrom,
                             List<String> sources) {

    public static final int MAX_IMAGES = 10;

    public ProductPrefill {
        imageUrls = List.copyOf(imageUrls);
        sourceCategories = List.copyOf(sourceCategories);
        importedFrom = Map.copyOf(importedFrom);
        sources = List.copyOf(sources);
    }

    public boolean found() {
        return !importedFrom.isEmpty();
    }

    public static ProductPrefill merge(List<ProductData> results) {
        Map<String, String> from = new LinkedHashMap<>();
        String title = first(results, ProductData::title, "title", from);
        String brand = first(results, ProductData::brand, "brand", from);
        String netContent = first(results, ProductData::netContent, "netContent", from);
        String description = first(results, ProductData::description, "description", from);
        Set<String> images = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        for (ProductData r : results) {
            if (!r.imageUrls().isEmpty() && images.isEmpty()) {
                from.put("imageUrls", r.source());
            }
            r.imageUrls().forEach(images::add);
            categories.addAll(r.sourceCategories());
            if (!r.empty() && !sources.contains(r.source())) {
                sources.add(r.source());
            }
        }
        return new ProductPrefill(title, brand, netContent, description,
                images.stream().limit(MAX_IMAGES).toList(), List.copyOf(categories), from, sources);
    }

    private static String first(List<ProductData> results, Function<ProductData, String> field, String name,
                                Map<String, String> from) {
        for (ProductData r : results) {
            String v = field.apply(r);
            if (v != null) {
                from.put(name, r.source());
                return v;
            }
        }
        return null;
    }
}
