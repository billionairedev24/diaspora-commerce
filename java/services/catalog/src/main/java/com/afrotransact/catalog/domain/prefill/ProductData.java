package com.afrotransact.catalog.domain.prefill;

import java.util.List;
import java.util.Objects;

/**
 * What one product-data provider knows about a GTIN.
 *
 * @param source           provider name ({@code GS1}, {@code OPEN_FOOD_FACTS})
 * @param gtin             the GTIN as the provider returned it
 * @param netContent       size / net content as printed, e.g. "500 g"
 * @param imageUrls        product image URLs (front first)
 * @param sourceCategories provider categories, most general first: Open Food Facts tags ({@code en:rices}) or
 *                         {@code gpc:<brick code>} for GS1
 */
public record ProductData(String source, String gtin, String title, String brand, String netContent,
                          String description, List<String> imageUrls, List<String> sourceCategories) {

    public ProductData {
        Objects.requireNonNull(source, "source");
        title = clean(title, 200);
        brand = clean(brand, 120);
        netContent = clean(netContent, 60);
        description = clean(description, 2000);
        imageUrls = imageUrls == null ? List.of() : imageUrls.stream().filter(Objects::nonNull).map(String::strip)
                .filter(u -> u.startsWith("https://") || u.startsWith("http://")).distinct().limit(10).toList();
        sourceCategories = sourceCategories == null ? List.of()
                : sourceCategories.stream().filter(Objects::nonNull).map(String::strip).filter(c -> !c.isEmpty())
                .toList();
    }

    /** True when the provider returned nothing a seller could use. */
    public boolean empty() {
        return title == null && brand == null && netContent == null && description == null && imageUrls.isEmpty();
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip().replaceAll("\\s+", " ");
        return v.length() <= max ? v : v.substring(0, max);
    }
}
