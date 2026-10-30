package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.BrandCatalogService;
import com.afrotransact.catalog.application.BrandCatalogService.BrandChoice;
import com.afrotransact.catalog.application.BrandCatalogService.BrandChoices;
import com.afrotransact.catalog.application.BrandCatalogService.BrandCreated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Brands", description = "The brands the product form offers for a category, and typing in a brand that is "
        + "not listed (S326)")
@RestController
class BrandController {

    private final BrandCatalogService brands;

    BrandController(BrandCatalogService brands) {
        this.brands = brands;
    }

    @Schema(name = "BrandList")
    record BrandList(UUID departmentId, String departmentName, List<BrandDto> brands) {

        static BrandList of(BrandChoices c) {
            return new BrandList(c.departmentId(), c.departmentName(), c.brands().stream().map(BrandDto::of).toList());
        }
    }

    @Schema(name = "Brand")
    record BrandDto(UUID id, String name, @Schema(allowableValues = {"OWN", "CATALOG"}) String source,
                    @Schema(allowableValues = {"APPROVED", "PENDING"}) String status, long ownProducts, long products) {

        static BrandDto of(BrandChoice c) {
            return new BrandDto(c.id(), c.name(), c.mine() ? "OWN" : "CATALOG", c.status().name(), c.ownProducts(),
                    c.products());
        }
    }

    @Schema(name = "CreateBrandRequest")
    record CreateBrandBody(@NotBlank @Size(max = 120) String name, @Size(max = 40) String productType,
                           @Size(max = 120) String category) {
    }

    @Schema(name = "CreatedBrand")
    record CreatedBrand(BrandDto brand, boolean created) {

        static CreatedBrand of(BrandCreated c) {
            return new CreatedBrand(BrandDto.of(c.brand()), c.created());
        }
    }

    @Operation(operationId = "listBrands", summary = "Brands for the product form's brand combobox",
            description = "Approved brands whose scope covers the category or product type (or an ancestor of it), plus "
                    + "the organisation's own pending brands there; the organisation's most used first, then the most "
                    + "popular. Never brands of unrelated departments. 422 when neither category nor productType is "
                    + "sent. q ignores case, accents and punctuation.")
    @GetMapping("/v1/brands")
    BrandList brands(@RequestParam(required = false) @Size(max = 40) String productType,
                     @RequestParam(required = false) @Size(max = 120) String category,
                     @RequestParam(required = false) @Size(max = 80) String q,
                     @RequestParam(defaultValue = "20") int limit) {
        return BrandList.of(brands.choices(productType, category, q, limit));
    }

    @Operation(operationId = "createBrand", summary = "Add a brand the seller typed (pending staff review)",
            description = "Returns the existing brand (created=false) when the registry already has this name in the "
                    + "category, ignoring case, accents and punctuation. Otherwise creates a PENDING brand scoped to the "
                    + "category's department, usable at once by this organisation. 201 when created, 200 for a match.")
    @PostMapping("/v1/brands")
    ResponseEntity<CreatedBrand> create(@Valid @RequestBody CreateBrandBody body) {
        CreatedBrand out = CreatedBrand.of(brands.create(body.name(), body.productType(), body.category()));
        return ResponseEntity.status(out.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(out);
    }
}
