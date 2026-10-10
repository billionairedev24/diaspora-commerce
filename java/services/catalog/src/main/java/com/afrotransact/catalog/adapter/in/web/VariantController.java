package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.adapter.in.web.ProductDtos.ProductResponse;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.VariantResponse;
import com.afrotransact.catalog.application.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Service lookup of a variant in any status (S226): AfroTransact Retail links draft first-party products. */
@Tag(name = "Products", description = "Product builder, listing moderation and lookup")
@RestController
class VariantController {

    private final ProductService products;

    VariantController(ProductService products) {
        this.products = products;
    }

    /** The product (any status, with moderation fields) and the requested variant. */
    @Schema(name = "VariantLookup")
    record VariantLookupResponse(ProductResponse product, VariantResponse variant) {

        static VariantLookupResponse of(ProductService.VariantLookup v) {
            return new VariantLookupResponse(ProductResponse.of(v.product()), VariantResponse.of(v.variant()));
        }
    }

    @Operation(summary = "A variant in any status with its product, by variant ATIN",
            description = "Service clients and staff with catalog:read (retail-service links draft AfroTransact Retail "
                    + "products before they go live); members of the owning organisation with catalog:write. Unlike "
                    + "GET /v1/public/variants/{atin}, drafts, products in review, paused, rejected, taken-down and "
                    + "hidden products are returned with their status. 404 catalog.variant-not-found.")
    @GetMapping("/v1/variants/{atin}")
    VariantLookupResponse variantAnyStatus(@PathVariable String atin) {
        return VariantLookupResponse.of(products.variant(atin));
    }
}
