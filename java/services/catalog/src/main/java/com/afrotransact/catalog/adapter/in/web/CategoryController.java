package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.CategoryDtos.ApprovalPlanResponse;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.ApproveCategoryRequest;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.CategoryRequestDetailResponse;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.CategoryNodeResponse;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.CategoryRequestResponse;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.CreateCategoryRequest;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.ProductTypeResponse;
import com.afrotransact.catalog.adapter.in.web.CategoryDtos.RejectCategoryRequest;
import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Categories", description = "Browse tree, product-type schemas and new-category requests")
@RestController
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @Operation(summary = "Full active category tree (department › category › product type)",
            description = "includeRetired=true also lists retired nodes, for consumers that resolve the category of "
                    + "an existing product.")
    @GetMapping("/v1/categories")
    List<CategoryNodeResponse> tree(@RequestParam(defaultValue = "false") boolean includeRetired) {
        return categories.tree(includeRetired).stream().map(CategoryNodeResponse::of).toList();
    }

    @Operation(summary = "All product-type schemas")
    @GetMapping("/v1/product-types")
    List<ProductTypeResponse> productTypes() {
        return categories.productTypes().stream().map(ProductTypeResponse::of).toList();
    }

    @Operation(summary = "Product-type schema: required details, variation themes, identifier rule")
    @GetMapping("/v1/product-types/{code}")
    ProductTypeResponse productType(@PathVariable String code) {
        return ProductTypeResponse.of(categories.productType(code));
    }

    @Operation(summary = "Request a new category (products can be built against it right away)")
    @PostMapping("/v1/category-requests")
    @ResponseStatus(HttpStatus.CREATED)
    CategoryRequestResponse request(@Valid @RequestBody CreateCategoryRequest body) {
        return CategoryRequestResponse.of(categories.request(body.parentPath(), body.name(), body.exampleProduct(),
                body.detailsNeeded()));
    }

    @Operation(summary = "My organisation's category requests")
    @GetMapping("/v1/category-requests/mine")
    List<CategoryRequestResponse> mine() {
        return categories.myRequests().stream().map(CategoryRequestResponse::of).toList();
    }

    @Operation(summary = "Review queue of category requests (catalog:taxonomy)")
    @GetMapping("/v1/category-requests")
    List<CategoryRequestResponse> queue(@RequestParam(required = false) CategoryRequestStatus status) {
        return categories.requests(status).stream()
                .map(q -> CategoryRequestResponse.of(q.request(), q.productsWaiting())).toList();
    }

    @Operation(summary = "One category request with the products waiting on it (catalog:taxonomy)")
    @GetMapping("/v1/category-requests/{id}")
    CategoryRequestDetailResponse requestDetail(@PathVariable UUID id) {
        return CategoryRequestDetailResponse.of(categories.requestDetail(id));
    }

    @Operation(summary = "What approving would create, without creating it (catalog:taxonomy)")
    @PostMapping("/v1/category-requests/{id}/approval-preview")
    ApprovalPlanResponse previewApproval(@PathVariable UUID id, @Valid @RequestBody ApproveCategoryRequest body) {
        return ApprovalPlanResponse.of(categories.planApproval(id, body.toChoice()));
    }

    @Operation(summary = "Approve a category request, creating its category and product type in one step; waiting "
            + "products move into listing review")
    @PostMapping("/v1/category-requests/{id}/approve")
    CategoryRequestResponse approve(@PathVariable UUID id, @Valid @RequestBody ApproveCategoryRequest body) {
        return CategoryRequestResponse.of(categories.approve(id, body.toChoice(), body.note()));
    }

    @Operation(summary = "Reject a category request; waiting products are rejected with the note")
    @PostMapping("/v1/category-requests/{id}/reject")
    CategoryRequestResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectCategoryRequest body) {
        return CategoryRequestResponse.of(categories.reject(id, body.note()));
    }
}
