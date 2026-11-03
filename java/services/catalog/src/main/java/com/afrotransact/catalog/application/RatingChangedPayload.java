package com.afrotransact.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.RatingSummary;

/**
 * Payload of {@code catalog.product.rating_changed} (contracts/asyncapi/fragments/catalog.yaml
 * CatalogProductRatingChanged): the product's new average, review count and star breakdown. The search indexer sets
 * the product document's rating and reviewCount from it (rating filter and sort, listing cards).
 *
 * @param average   average stars over published reviews, two decimals (0 when there are none)
 * @param count     published reviews
 * @param breakdown counts for 1, 2, 3, 4 and 5 stars
 */
public record RatingChangedPayload(UUID productId, String atin, String ownerOrgId, BigDecimal average, int count,
                                   List<Integer> breakdown) {

    static RatingChangedPayload of(Product p, RatingSummary s) {
        return new RatingChangedPayload(p.id(), p.atin().value(), p.owner().orgId(), s.average(), s.count(),
                s.breakdown());
    }
}
