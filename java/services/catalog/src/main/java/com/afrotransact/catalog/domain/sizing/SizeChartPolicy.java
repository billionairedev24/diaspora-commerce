package com.afrotransact.catalog.domain.sizing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariantDraft;

/**
 * The size rules of a listing, decided by its product type's sizing trait: whether a chart is required, whether the
 * chart belongs to the seller, and that every size the variants sell has a row in it.
 */
public interface SizeChartPolicy {

    /**
     * The problems of the listing's size chart by field ({@code sizeChartId}, {@code variants[2].options.Size}); empty
     * when it is fine.
     */
    Map<String, String> problems(SizedListing listing);

    /**
     * What the rules need to know about a listing.
     *
     * @param ownerOrg      the organisation that owns the listing
     * @param variationTheme the listing's variation theme name (may be empty)
     * @param variants      the listing's variants
     * @param chartId       the chart the listing links; null for none
     * @param chart         the chart with that id as stored, whoever owns it; null when there is none
     * @param linkedChartId the chart the listing already links in its stored form (a retired chart may stay linked)
     */
    record SizedListing(ProductType type, String ownerOrg, String variationTheme, List<VariantDraft> variants,
                        UUID chartId, SizeChart chart, UUID linkedChartId) {

        public SizedListing {
            variants = variants == null ? List.of() : List.copyOf(variants);
            variationTheme = variationTheme == null ? "" : variationTheme;
        }
    }
}
