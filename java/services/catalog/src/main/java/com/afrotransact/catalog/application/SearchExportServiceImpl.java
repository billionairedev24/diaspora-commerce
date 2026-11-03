package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository.Decision;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductReviewRepository;
import com.afrotransact.catalog.port.ProductReviewRepository.RatingSnapshot;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SearchExportServiceImpl implements SearchExportService {

    private final ProductRepository products;
    private final ProductReviewRepository reviews;
    private final ListingSuppressionRepository suppressions;
    private final PermissionChecker permissions;

    SearchExportServiceImpl(ProductRepository products, ProductReviewRepository reviews,
                            ListingSuppressionRepository suppressions, PermissionChecker permissions) {
        this.products = products;
        this.reviews = reviews;
        this.suppressions = suppressions;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchExportPage export(Set<ProductStatus> statuses, UUID afterId, Instant since, int size) {
        permissions.require(CatalogPermissions.READ);
        Set<ProductStatus> wanted = statuses == null || statuses.isEmpty() ? EnumSet.of(ProductStatus.LIVE) : statuses;
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        // One row more than the page tells whether another page follows.
        List<Product> found = products.findForExport(wanted, afterId, since, limit + 1);
        boolean more = found.size() > limit;
        List<Product> page = more ? found.subList(0, limit) : found;

        Set<UUID> ids = new HashSet<>();
        Set<String> orgs = new HashSet<>();
        for (Product p : page) {
            ids.add(p.id());
            orgs.add(p.owner().orgId());
        }
        Map<UUID, RatingSnapshot> ratings = reviews.ratingSnapshots(ids);
        Map<String, Decision> decisions = suppressions.decisions(orgs);

        List<SearchExportItem> items = page.stream()
                .map(p -> item(p, ratings.get(p.id()), decisions.get(p.owner().orgId()))).toList();
        return new SearchExportPage(items, more ? page.getLast().id() : null);
    }

    private static SearchExportItem item(Product p, RatingSnapshot rating, Decision decision) {
        boolean hidden = decision != null && decision.hidden();
        Instant version = p.updatedAt();
        if (decision != null && decision.changedAt().isAfter(version)) {
            version = decision.changedAt();
        }
        SearchExportRating exported = rating == null ? null : new SearchExportRating(rating.summary().average(),
                rating.summary().count(), rating.updatedAt());
        return new SearchExportItem(ProductEventPayload.of(p, hidden), version, exported);
    }
}
