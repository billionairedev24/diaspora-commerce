package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.TaxonomyAdminService.CategoryNodeView;
import com.afrotransact.catalog.application.TaxonomyAdminService.NewProductType;
import com.afrotransact.catalog.application.TaxonomyAdminService.ProductTypeView;
import com.afrotransact.catalog.application.TaxonomyAdminService.TaxonomyTree;
import com.afrotransact.catalog.application.TaxonomyAdminService.TypeUse;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the staff taxonomy administration API. */
final class TaxonomyDtos {

    private TaxonomyDtos() {
    }

    @Schema(name = "TaxonomyNode")
    record TaxonomyNodeResponse(UUID id, UUID parentId, String code, String name, String level, String productType,
                                String productTypeLabel, String path, int position, boolean active,
                                Instant retiredAt, String retiredReason, long version, long productCount,
                                long subtreeProductCount, int childCount) {

        static TaxonomyNodeResponse of(CategoryNodeView v) {
            CategoryNode n = v.node();
            return new TaxonomyNodeResponse(n.id(), n.parentId(), n.code(), n.name(), n.level().name(),
                    n.productTypeCode(), v.productTypeLabel(), n.path(), n.position(), n.active(), n.retiredAt(),
                    n.retiredReason(), n.version(), v.productCount(), v.subtreeProductCount(), v.childCount());
        }
    }

    @Schema(name = "TaxonomyTree")
    record TaxonomyTreeResponse(List<TaxonomyNodeResponse> nodes, long pendingRequests) {

        static TaxonomyTreeResponse of(TaxonomyTree t) {
            return new TaxonomyTreeResponse(t.nodes().stream().map(TaxonomyNodeResponse::of).toList(),
                    t.pendingRequests());
        }
    }

    @Schema(name = "TaxonomyTypeUse")
    record TypeUseResponse(UUID nodeId, String path, boolean active) {

        static TypeUseResponse of(TypeUse u) {
            return new TypeUseResponse(u.nodeId(), u.path(), u.active());
        }
    }

    @Schema(name = "TaxonomyProductType", description = "A product-type schema as staff edit it: every attribute "
            + "including the ones that do not apply, the version to send as If-Match, and where it is used")
    record ProductTypeAdminResponse(String code, String label, String identifierRule, ProductTypeTraits traits,
                                    List<AttributeDefinition> attributes, List<VariationTheme> themes, long version,
                                    Instant updatedAt, long productCount, List<TypeUseResponse> usedBy) {

        static ProductTypeAdminResponse of(ProductTypeView v) {
            ProductType t = v.stored().type();
            return new ProductTypeAdminResponse(t.code(), t.label(), t.identifierRule().name(), t.traits(),
                    t.attributes(), t.themes(), v.stored().version(), v.stored().updatedAt(), v.productCount(),
                    v.usedBy().stream().map(TypeUseResponse::of).toList());
        }
    }

    @Schema(name = "TaxonomyAuditEntry")
    record AuditResponse(UUID id, Instant at, String actorId, String action, String subjectKind, String subjectId,
                         String subjectLabel, String reason, Map<String, Object> details) {

        static AuditResponse of(TaxonomyAuditEntry e) {
            return new AuditResponse(e.id(), e.occurredAt(), e.actorId(), e.action(), e.subjectKind().name(),
                    e.subjectId(), e.subjectLabel(), e.reason(), e.details());
        }
    }

    @Schema(name = "TaxonomyTraits")
    record TraitsBody(Boolean food, Boolean perishable, Boolean regulated, Boolean preorder) {

        ProductTypeTraits toDomain() {
            return new ProductTypeTraits(Boolean.TRUE.equals(food), Boolean.TRUE.equals(perishable),
                    Boolean.TRUE.equals(regulated), Boolean.TRUE.equals(preorder));
        }
    }

    @Schema(name = "TaxonomyAttribute", description = "One detail a product type asks for. applies=false keeps the "
            + "definition but stops asking (brand is just an attribute; a Books type has none).")
    record AttributeBody(@NotBlank @Size(max = 40) String key, @NotBlank @Size(max = 120) String label,
                         List<@Size(max = 80) String> options, Boolean required, @NotNull AttributeKind kind,
                         List<@Size(max = 20) String> units, AttributeSection section, @Size(max = 200) String help,
                         Boolean applies) {

        AttributeDefinition toDomain() {
            return new AttributeDefinition(key, label, options, Boolean.TRUE.equals(required),
                    kind == AttributeKind.MULTILINE, kind, units, section, help, applies);
        }
    }

    @Schema(name = "TaxonomyNewProductType")
    record NewProductTypeBody(@Size(max = 40) String code, @NotBlank @Size(max = 80) String label,
                              @NotNull IdentifierRule identifierRule, @Valid TraitsBody traits,
                              @Valid List<AttributeBody> attributes, @Size(max = 40) String basedOn) {

        NewProductType toDomain() {
            return new NewProductType(code, label, identifierRule, traits == null ? null : traits.toDomain(),
                    attributes == null ? List.of() : attributes.stream().map(AttributeBody::toDomain).toList(),
                    basedOn);
        }
    }

    @Schema(name = "CreateTaxonomyNode")
    record CreateNodeBody(@NotNull CategoryLevel level, UUID parentId, @NotBlank @Size(max = 80) String name,
                          @Size(max = 120) String code, @Size(max = 40) String productType,
                          @Valid NewProductTypeBody newProductType, @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "RenameTaxonomyNode")
    record RenameBody(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "MoveTaxonomyNode")
    record MoveBody(@NotNull UUID parentId, @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "TaxonomyReason")
    record ReasonBody(@NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "ReorderTaxonomyNodes", description = "parentId null orders the departments; ids must be every "
            + "child of the parent, retired ones included")
    record ReorderBody(UUID parentId, @NotNull List<UUID> ids, @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "CreateTaxonomyProductType")
    record CreateTypeBody(@Size(max = 40) String code, @NotBlank @Size(max = 80) String label,
                          @NotNull IdentifierRule identifierRule, @Valid TraitsBody traits,
                          @Valid List<AttributeBody> attributes, @Size(max = 40) String basedOn,
                          @NotBlank @Size(max = 1000) String reason) {

        NewProductType toDomain() {
            return new NewProductTypeBody(code, label, identifierRule, traits, attributes, basedOn).toDomain();
        }
    }

    @Schema(name = "UpdateTaxonomyProductType", description = "Only the fields sent change; attributes, when sent, "
            + "is the whole new list")
    record UpdateTypeBody(@Size(max = 80) String label, IdentifierRule identifierRule, @Valid TraitsBody traits,
                          @Valid List<AttributeBody> attributes, @NotBlank @Size(max = 1000) String reason) {
    }
}
