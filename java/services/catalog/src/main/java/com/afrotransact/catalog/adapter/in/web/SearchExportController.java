package com.afrotransact.catalog.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.ProductEventPayload;
import com.afrotransact.catalog.application.SearchExportService;
import com.afrotransact.catalog.application.SearchExportService.SearchExportItem;
import com.afrotransact.catalog.application.SearchExportService.SearchExportPage;
import com.afrotransact.catalog.application.SearchExportService.SearchExportRating;
import com.afrotransact.catalog.domain.ProductStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Search export", description = "Read-only enumeration of products for rebuilding the search index "
        + "(indexer reindex command)")
@RestController
class SearchExportController {

    private final SearchExportService export;

    SearchExportController(SearchExportService export) {
        this.export = export;
    }

    @Operation(operationId = "searchExportProducts",
            summary = "Products as catalog.product.* event payloads, keyset-paged by id, for the search index rebuild",
            description = "Service clients with catalog:read (the indexer-service client) and staff. Each item is the "
                    + "payload the indexer would have received for the product's current state (sellerHidden while "
                    + "the owner's listings are hidden), the time that state last changed (version) and the stored "
                    + "rating. Page with after = nextAfter until nextAfter is null. Read-only; publishes nothing.")
    @GetMapping("/v1/search-export/products")
    SearchExportPageResponse products(
            @Parameter(description = "Only these statuses (default LIVE)") @RequestParam(required = false)
            List<ProductStatus> status,
            @Parameter(description = "Exclusive lower bound: the nextAfter of the previous page")
            @RequestParam(required = false) UUID after,
            @Parameter(description = "Only products changed (own data, rating or owner's listing visibility) at or "
                    + "after this instant") @RequestParam(required = false)
            Instant since,
            @Parameter(description = "Page size, 1 to 200") @RequestParam(defaultValue = "100") int size) {
        Set<ProductStatus> statuses = EnumSet.noneOf(ProductStatus.class);
        if (status != null) {
            statuses.addAll(status);
        }
        return SearchExportPageResponse.of(export.export(statuses, after, since, size));
    }

    @Schema(name = "SearchExportRating")
    record RatingResponse(BigDecimal average, int count, Instant updatedAt) {

        static RatingResponse of(SearchExportRating r) {
            return r == null ? null : new RatingResponse(r.average(), r.count(), r.updatedAt());
        }
    }

    @Schema(name = "SearchExportItem")
    record ItemResponse(ProductEventPayload product,
                        @Schema(description = "When the product's state last changed") Instant version,
                        @Schema(description = "Null when the product never had a published review")
                        RatingResponse rating) {

        static ItemResponse of(SearchExportItem i) {
            return new ItemResponse(i.product(), i.version(), RatingResponse.of(i.rating()));
        }
    }

    @Schema(name = "SearchExportPage")
    record SearchExportPageResponse(List<ItemResponse> items,
                                    @Schema(description = "after for the next page; null on the last page")
                                    UUID nextAfter) {

        static SearchExportPageResponse of(SearchExportPage page) {
            return new SearchExportPageResponse(page.items().stream().map(ItemResponse::of).toList(), page.nextAfter());
        }
    }
}
