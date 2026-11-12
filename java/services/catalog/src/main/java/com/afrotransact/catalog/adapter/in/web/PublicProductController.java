package com.afrotransact.catalog.adapter.in.web;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ProductDtos.ImageDto;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.VariantResponse;
import com.afrotransact.catalog.adapter.in.web.ReviewDtos.RatingResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.PublicChartResponse;
import com.afrotransact.catalog.application.ProductReviewService;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.application.PublicCatalogService.LiveVariant;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.platform.web.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public catalog", description = "Anonymous reads of live products (storefront, cart, search)")
@SecurityRequirements
@RestController
@RequestMapping("/v1/public")
class PublicProductController {

    private final PublicCatalogService catalog;
    private final ProductReviewService reviews;

    PublicProductController(PublicCatalogService catalog, ProductReviewService reviews) {
        this.catalog = catalog;
        this.reviews = reviews;
    }

    @Schema(name = "PublicProduct")
    record PublicProductResponse(UUID id, String atin, String ownerKind, String sellerOrgId, UUID categoryId,
                                 String productType, String title, Map<String, String> attributes,
                                 List<ImageDto> images, String variationTheme, List<VariantResponse> variants,
                                 boolean preorderEligible, boolean shipsNow,
                                 @Schema(description = "Average stars, review count and star breakdown over the "
                                         + "product's published reviews (S324); zeros without reviews")
                                 RatingResponse rating,
                                 @Schema(description = "The seller's size chart (columns, rows in the chart's unit and "
                                         + "converted, fit note, how to measure) when the product has one")
                                 PublicChartResponse sizeChart,
                                 @Schema(description = "The tax profile (category code) tax-returns decides this "
                                         + "product's tax by; null while the category has none. Read by checkout "
                                         + "and tax-returns, not for browsing.")
                                 String categoryCode,
                                 @Schema(description = "The seller's answers about how the item is sold (the tax "
                                         + "behaviour facts); nothing else of the listing is public")
                                 PublicListingResponse listing) {

        static PublicProductResponse of(Product p, RatingResponse rating, PublicChartResponse sizeChart,
                                        String categoryCode) {
            return new PublicProductResponse(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                    p.categoryId(), p.productTypeCode(), p.title(), p.attributes(),
                    p.images().stream().map(ImageDto::of).toList(), p.variationTheme(),
                    p.variants().stream().map(VariantResponse::of).toList(), p.preorderEligible(), p.shipsNow(), rating,
                    sizeChart, categoryCode, new PublicListingResponse(p.details().sellerBehaviour()));
        }
    }

    @Schema(name = "PublicListing")
    record PublicListingResponse(Map<String, Object> sellerBehaviour) {
    }

    @Schema(name = "PublicVariant")
    record PublicVariantResponse(PublicProductResponse product, VariantResponse variant) {
    }

    @Schema(name = "AtinRedirect")
    record AtinRedirectResponse(String atin,
                                @Schema(description = "ATIN of the page that survived the merge") String redirectTo) {
    }

    @Operation(operationId = "atinRedirect", summary = "Where an ATIN went after two duplicate pages were merged",
            description = "Anonymous. 200 with the surviving page ATIN, 404 when the ATIN was never merged away.")
    @GetMapping("/products/{atin}/redirect")
    AtinRedirectResponse redirect(@PathVariable String atin) {
        String target = catalog.redirectOf(atin).orElseThrow(() -> new NotFoundException("catalog.redirect-not-found",
                "The ATIN " + atin + " was never merged into another page"));
        return new AtinRedirectResponse(atin.toUpperCase(Locale.ROOT), target);
    }

    @Operation(summary = "A live product page by product ATIN")
    @GetMapping("/products/{atin}")
    ResponseEntity<PublicProductResponse> product(@PathVariable String atin) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(withRating(catalog.liveProduct(atin)));
    }

    @Operation(summary = "A live variant (with its product) by variant ATIN — used by cart and checkout")
    @GetMapping("/variants/{atin}")
    ResponseEntity<PublicVariantResponse> variant(@PathVariable String atin) {
        LiveVariant lv = catalog.liveVariant(atin);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(new PublicVariantResponse(withRating(lv.product()), VariantResponse.of(lv.variant())));
    }

    private PublicProductResponse withRating(Product product) {
        PublicChartResponse chart = catalog.sizeChartOf(product)
                .map(c -> PublicChartResponse.of(c, product.details().fitNote())).orElse(null);
        return PublicProductResponse.of(product, RatingResponse.of(reviews.ratingOf(product.id())), chart,
                catalog.taxProfileOf(product).orElse(null));
    }
}
