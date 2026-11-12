package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;

/**
 * Merges a duplicate page into the page that survives (S386): the duplicate becomes MERGED (never on sale again), its
 * page ATIN and every variant ATIN redirect to the survivor, its barcodes are released, and
 * {@code catalog.product.merged} tells offers to move the offers of the merged variants to the mapped variants.
 */
public interface PageMerger {

    /**
     * @param variantMap loser variant ATIN to survivor variant ATIN; variants not named are mapped by barcode, then by
     *                   identical options, then (one variant each) to each other. Every loser variant must end up mapped.
     * @throws com.afrotransact.platform.web.error.BusinessRuleException {@code catalog.merge-unmapped-variant} when a
     *         variant cannot be mapped, {@code catalog.merge-invalid} for pages that cannot be merged
     */
    Merged merge(Product loser, Product survivor, Map<String, String> variantMap, String by, String reason,
                 UUID changeRequestId, Instant now);

    /** @param variantMap the complete map that was applied */
    record Merged(Product loser, Product survivor, Map<String, String> variantMap) {
    }
}
