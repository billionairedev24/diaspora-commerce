package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationTheme;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(name = "ProductType")
    record ProductTypeResponse(String code, String label, String identifierRule, List<AttributeDefinition> attributes,
                               List<VariationTheme> themes) {

        static ProductTypeResponse of(ProductType t) {
            return new ProductTypeResponse(t.code(), t.label(), t.identifierRule().name(), t.attributes(), t.themes());
        }
    }

    @Schema(name = "CreateCategoryRequest")
    record CreateCategoryRequest(@NotBlank @Size(min = 3, max = 200) String parentPath,
                                 @NotBlank @Size(min = 3, max = 80) String name,
                                 @NotBlank @Size(min = 3, max = 200) String exampleProduct,
                                 @NotBlank @Size(min = 3, max = 1000) String detailsNeeded) {
    }

    @Schema(name = "ApproveCategoryRequest")
    record ApproveCategoryRequest(@NotNull UUID parentCategoryId, @NotBlank String productType, String note) {
    }

    @Schema(name = "RejectCategoryRequest")
    record RejectCategoryRequest(@NotBlank @Size(min = 3, max = 1000) String note) {
    }

    @Schema(name = "CategoryRequest")
    record CategoryRequestResponse(UUID id, String reference, String orgId, String path, String exampleProduct,
                                   String detailsNeeded, String status, UUID resolvedCategoryId, String decisionNote,
                                   Instant createdAt, Instant decidedAt) {

        static CategoryRequestResponse of(CategoryRequest r) {
            return new CategoryRequestResponse(r.id(), r.reference(), r.requestedByOrg(), r.fullPath(),
                    r.exampleProduct(), r.detailsNeeded(), r.status().name(), r.resolvedCategoryId(),
                    r.decisionNote(), r.createdAt(), r.decidedAt());
        }
    }
}
