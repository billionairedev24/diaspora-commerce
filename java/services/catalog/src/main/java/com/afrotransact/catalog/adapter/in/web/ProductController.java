package com.afrotransact.catalog.adapter.in.web;

import java.net.URI;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ProductDtos.CreateProductRequest;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.NoteRequest;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.ProductPageResponse;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.ProductResponse;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Products", description = "Product builder, listing moderation and lookup")
@RestController
@RequestMapping("/v1/products")
class ProductController {

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @Operation(summary = "Create a product and submit it for review",
            description = "The listing owner (seller org or AfroTransact Retail) is derived from the token.")
    @PostMapping
    ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest body) {
        Product created = products.create(body.toCommand());
        return ResponseEntity.created(URI.create("/v1/products/" + created.id())).body(ProductResponse.of(created));
    }

    @Operation(summary = "List products (own organisation; staff may filter by org)")
    @GetMapping
    ProductPageResponse list(@RequestParam(required = false) String orgId,
                             @RequestParam(required = false) ProductStatus status,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        PageView<Product> result = products.list(orgId, status, q, page, size);
        return new ProductPageResponse(result.items().stream().map(ProductResponse::of).toList(), result.total(),
                result.page(), result.size());
    }

    @Operation(summary = "Get a product by id")
    @GetMapping("/{id}")
    ProductResponse get(@PathVariable UUID id) {
        return ProductResponse.of(products.get(id));
    }

    @Operation(summary = "Get a product by ATIN")
    @GetMapping("/atin/{atin}")
    ProductResponse byAtin(@PathVariable String atin) {
        return ProductResponse.of(products.getByAtin(atin));
    }

    @Operation(summary = "Approve a product in review (catalog:moderate)")
    @PostMapping("/{id}/approve")
    ProductResponse approve(@PathVariable UUID id) {
        return ProductResponse.of(products.approve(id));
    }

    @Operation(summary = "Reject a product with a note (catalog:moderate)")
    @PostMapping("/{id}/reject")
    ProductResponse reject(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return ProductResponse.of(products.reject(id, body.note()));
    }

    @Operation(summary = "Pause a live product")
    @PostMapping("/{id}/pause")
    ProductResponse pause(@PathVariable UUID id) {
        return ProductResponse.of(products.pause(id));
    }

    @Operation(summary = "Resume a paused product")
    @PostMapping("/{id}/resume")
    ProductResponse resume(@PathVariable UUID id) {
        return ProductResponse.of(products.resume(id));
    }
}
