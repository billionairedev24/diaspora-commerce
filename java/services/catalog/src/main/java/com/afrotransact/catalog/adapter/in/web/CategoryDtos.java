package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.CategoryService.ApprovalChoice;
import com.afrotransact.catalog.application.CategoryService.ApprovalPlan;
import com.afrotransact.catalog.application.CategoryService.PlannedNode;
import com.afrotransact.catalog.application.CategoryService.RequestDetail;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationTheme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for categories, product types and category requests. */
final class CategoryDtos {

    private CategoryDtos() {
    }

    @Schema(name = "CategoryNode")
    record CategoryNodeResponse(UUID id, UUID parentId, String name, String level, String productType, String path) {

        static CategoryNodeResponse of(CategoryNode n) {
            return new CategoryNodeResponse(n.id(), n.parentId(), n.name(), n.level().name(), n.productTypeCode(),
                    n.path());
        }
    }

    @Schema(name = "ProductType", description = "Attributes carry kind (TEXT, MULTILINE, ENUM, NUMBER), units, the "
            + "form section (DETAILS, FOOD, REGULATED) and a help line; traits decide the conditional form sections")
    record ProductTypeResponse(String code, String label, String identifierRule, List<AttributeDefinition> attributes,
                               List<VariationTheme> themes, ProductTypeTraits traits) {

        static ProductTypeResponse of(ProductType t) {
            return new ProductTypeResponse(t.code(), t.label(), t.identifierRule().name(), t.applicableAttributes(), t.themes(),
                    t.traits());
        }
    }

    @Schema(name = "CreateCategoryRequest")
    record CreateCategoryRequest(@NotBlank @Size(min = 3, max = 200) String parentPath,
                                 @NotBlank @Size(min = 3, max = 80) String name,
                                 @NotBlank @Size(min = 3, max = 200) String exampleProduct,
                                 @NotBlank @Size(min = 3, max = 1000) String detailsNeeded) {
    }

    @Schema(name = "ApproveCategoryRequest", description = "parentCategoryId is a department (a category named "
            + "categoryName, default the requested name, is created for it) or a category. Give productType (an "
            + "existing schema) or newProductType (a new one), not both.")
    record ApproveCategoryRequest(@NotNull UUID parentCategoryId, @Size(max = 80) String categoryName,
                                  @Size(max = 40) String productType,
                                  @Valid TaxonomyDtos.NewProductTypeBody newProductType,
                                  @Size(max = 1000) String note) {

        ApprovalChoice toChoice() {
            return new ApprovalChoice(parentCategoryId, categoryName, productType,
                    newProductType == null ? null : newProductType.toDomain());
        }
    }

    @Schema(name = "PlannedNode")
    record PlannedNodeResponse(String level, String name, String path, boolean created) {

        static PlannedNodeResponse of(PlannedNode n) {
            return new PlannedNodeResponse(n.level().name(), n.name(), n.path(), n.created());
        }
    }

    @Schema(name = "PlannedProductType")
    record PlannedTypeResponse(String code, String label, boolean created, int attributeCount) {
    }

    @Schema(name = "ApprovalPlan", description = "What approving the request would create; nothing is created by "
            + "asking")
    record ApprovalPlanResponse(List<PlannedNodeResponse> nodes, PlannedTypeResponse productType,
                                long productsMoving) {

        static ApprovalPlanResponse of(ApprovalPlan p) {
            return new ApprovalPlanResponse(p.nodes().stream().map(PlannedNodeResponse::of).toList(),
                    new PlannedTypeResponse(p.productType().code(), p.productType().label(),
                            p.productType().created(), p.productType().attributeCount()), p.productsMoving());
        }
    }

    @Schema(name = "RejectCategoryRequest")
    record RejectCategoryRequest(@NotBlank @Size(min = 3, max = 1000) String note) {
    }

    @Schema(name = "CategoryRequest")
    record CategoryRequestResponse(UUID id, String reference, String orgId, String path, String parentPath,
                                   String name, String exampleProduct, String detailsNeeded, String status,
                                   UUID resolvedCategoryId, String decisionNote, Instant createdAt, Instant decidedAt,
                                   long productsWaiting) {

        static CategoryRequestResponse of(CategoryRequest r) {
            return of(r, 0);
        }

        static CategoryRequestResponse of(CategoryRequest r, long productsWaiting) {
            return new CategoryRequestResponse(r.id(), r.reference(), r.requestedByOrg(), r.fullPath(),
                    r.parentPath(), r.name(), r.exampleProduct(), r.detailsNeeded(), r.status().name(),
                    r.resolvedCategoryId(), r.decisionNote(), r.createdAt(), r.decidedAt(), productsWaiting);
        }
    }

    @Schema(name = "WaitingProduct")
    record WaitingProductResponse(UUID id, String atin, String title) {

        static WaitingProductResponse of(Product p) {
            return new WaitingProductResponse(p.id(), p.atin().value(), p.title());
        }
    }

    @Schema(name = "CategoryRequestDetail", description = "A request with the products waiting on it and the "
            + "department or category its requested path points to (null when the path does not exist)")
    record CategoryRequestDetailResponse(CategoryRequestResponse request, List<WaitingProductResponse> products,
                                         CategoryNodeResponse suggestedParent) {

        static CategoryRequestDetailResponse of(RequestDetail d) {
            return new CategoryRequestDetailResponse(
                    CategoryRequestResponse.of(d.request(), d.waitingProducts().size()),
                    d.waitingProducts().stream().map(WaitingProductResponse::of).toList(),
                    d.suggestedParent() == null ? null : CategoryNodeResponse.of(d.suggestedParent()));
        }
    }
}
