package com.afrotransact.catalog.adapter.in.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ProductDtos.ImageDto;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.VariantResponse;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.application.PublicCatalogService.LiveVariant;
import com.afrotransact.catalog.domain.Product;
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

    PublicProductController(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @Schema(name = "PublicProduct")
    record PublicProductResponse(UUID id, String atin, String ownerKind, String sellerOrgId, UUID categoryId,
                                 String productType, String title, Map<String, String> attributes,
                                 List<ImageDto> images, String variationTheme, List<VariantResponse> variants,
                                 boolean preorderEligible, boolean shipsNow) {

        static PublicProductResponse of(Product p) {
            return new PublicProductResponse(p.id(), p.atin().value(), p.owner().kind().name(), p.owner().orgId(),
                    p.categoryId(), p.productTypeCode(), p.title(), p.attributes(),
                    p.images().stream().map(ImageDto::of).toList(), p.variationTheme(),
                    p.variants().stream().map(VariantResponse::of).toList(), p.preorderEligible(), p.shipsNow());
        }
    }

    @Schema(name = "PublicVariant")
    record PublicVariantResponse(PublicProductResponse product, VariantResponse variant) {
    }

    @Operation(summary = "A live product page by product ATIN")
    @GetMapping("/products/{atin}")
    ResponseEntity<PublicProductResponse> product(@PathVariable String atin) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(PublicProductResponse.of(catalog.liveProduct(atin)));
    }

    @Operation(summary = "A live variant (with its product) by variant ATIN — used by cart and checkout")
    @GetMapping("/variants/{atin}")
    ResponseEntity<PublicVariantResponse> variant(@PathVariable String atin) {
        LiveVariant lv = catalog.liveVariant(atin);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(new PublicVariantResponse(PublicProductResponse.of(lv.product()), VariantResponse.of(lv.variant())));
    }
}
