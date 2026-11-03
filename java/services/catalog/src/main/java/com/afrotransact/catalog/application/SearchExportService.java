package com.afrotransact.catalog.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductStatus;

/**
 * Read-only enumeration of the products for the search index rebuild (the indexer's reindex command). Each item is the
 * {@code catalog.product.*} event payload the indexer would have received for the product's current state, so the
 * indexer projects it with the code it uses for events; service clients holding {@code catalog:read} and staff only.
 */
public interface SearchExportService {

    /** Largest page the export returns. */
    int MAX_PAGE_SIZE = 200;

    /**
     * One keyset page of products ordered by id.
     *
     * @param statuses only products in these statuses (empty = LIVE)
     * @param afterId  exclusive lower bound, null for the first page
     * @param since    only products changed (own data, rating or owner's listing visibility) at or after; null = all
     * @param size     page size, clamped to 1..{@link #MAX_PAGE_SIZE}
     */
    SearchExportPage export(Set<ProductStatus> statuses, UUID afterId, Instant since, int size);

    /**
     * One page; {@code nextAfter} is the {@code afterId} of the next page, null on the last page.
     */
    record SearchExportPage(List<SearchExportItem> items, UUID nextAfter) {

        public SearchExportPage {
            items = List.copyOf(items);
        }
    }

    /**
     * A product's current state as an event payload.
     *
     * @param product the payload, {@code sellerHidden} set while the owner's listings are hidden
     * @param version when this state was last changed: the later of the product's update time and the owner's last
     *                hide / restore decision. Events about a later change carry a later time, which is how the indexer's
     *                version guard lets them win over this export.
     * @param rating  the product's stored rating; null when it never had a published review
     */
    record SearchExportItem(ProductEventPayload product, Instant version, SearchExportRating rating) {
    }

    /** A product's rating and when it was last recomputed. */
    record SearchExportRating(BigDecimal average, int count, Instant updatedAt) {
    }
}
