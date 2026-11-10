package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.TaxonomyAdminService.CategoryNodeView;
import com.afrotransact.catalog.application.TaxonomyAdminService.NewProductType;
import com.afrotransact.catalog.application.TaxonomyAdminService.ProductTypeView;
import com.afrotransact.catalog.application.TaxonomyAdminService.SizingEdit;
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
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizingTrait;
import com.afrotransact.catalog.domain.sizing.SuggestedMeasurement;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
                                long subtreeProductCount, int childCount, String taxProfileId, String taxStatus) {

        static TaxonomyNodeResponse of(CategoryNodeView v) {
            CategoryNode n = v.node();
            return new TaxonomyNodeResponse(n.id(), n.parentId(), n.code(), n.name(), n.level().name(),
                    n.productTypeCode(), v.productTypeLabel(), n.path(), n.position(), n.active(), n.retiredAt(),
                    n.retiredReason(), n.version(), v.productCount(), v.subtreeProductCount(), v.childCount(), n.taxProfileId(),
                    n.taxStatus());
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

    @Schema(name = "TaxonomyTraits", description = "The flags replace the stored flags. sizing: absent leaves the "
            + "sizing trait as it is, {enabled: false} switches it off; otherwise the fields sent change and the "
            + "others keep their value (suggestedMeasurements, when sent, is the whole new list)")
    record TraitsBody(Boolean food, Boolean perishable, Boolean regulated, Boolean preorder,
                      @Valid SizingBody sizing) {

        /** The flags and, for a new product type, the sizing trait. */
        ProductTypeTraits toDomain() {
            return new ProductTypeTraits(Boolean.TRUE.equals(food), Boolean.TRUE.equals(perishable),
                    Boolean.TRUE.equals(regulated), Boolean.TRUE.equals(preorder),
                    sizing == null ? null : sizing.toDomain());
        }

        /** The sizing edit of an update: null when the body does not mention sizing. */
        SizingEdit sizingEdit() {
            return sizing == null ? null : sizing.toEdit();
        }
    }

    @Schema(name = "TaxonomySizing", description = "Marks the product type as sized: sellers attach size charts they "
            + "write themselves and write their own size labels (the size variation then allows custom values). "
            + "suggestedMeasurements pre-fill the seller's chart editor and the bulk template.")
    record SizingBody(@Schema(description = "false switches sizing off; default true") Boolean enabled,
                      @Schema(description = "A listing needs a size chart whose rows cover every size it sells")
                      Boolean sizeChartRequired,
                      @Schema(description = "The variation dimension that holds the size; default Size")
                      @Size(max = 40) String sizeDimension,
                      @Size(max = 12) List<@Valid MeasurementBody> suggestedMeasurements) {

        /** The edit of an update: fields not sent keep what the product type has. */
        SizingEdit toEdit() {
            return new SizingEdit(Boolean.FALSE.equals(enabled), sizeChartRequired, sizeDimension,
                    suggestedMeasurements == null ? null
                            : suggestedMeasurements.stream().map(MeasurementBody::toDomain).toList());
        }

        /** The trait of a new product type; null when disabled. */
        SizingTrait toDomain() {
            if (Boolean.FALSE.equals(enabled)) {
                return null;
            }
            return new SizingTrait(sizeChartRequired, sizeDimension, suggestedMeasurements == null ? List.of()
                    : suggestedMeasurements.stream().map(MeasurementBody::toDomain).toList());
        }
    }

    @Schema(name = "TaxonomySuggestedMeasurement")
    record MeasurementBody(@Size(max = 40) String key, @NotBlank @Size(max = 40) String label, MeasurementUnit unit,
                           @Size(max = 200) String help) {

        SuggestedMeasurement toDomain() {
            return new SuggestedMeasurement(key, label, unit, help);
        }
    }

    @Schema(name = "TaxonomyVariationTheme", description = "One way a product varies: one or two dimensions")
    record ThemeBody(@NotBlank @Size(max = 60) String name,
                     @NotEmpty @Size(max = 2) List<@Valid DimensionBody> dimensions) {

        VariationTheme toDomain() {
            return new VariationTheme(name, dimensions.stream().map(DimensionBody::toDomain).toList());
        }
    }

    @Schema(name = "TaxonomyVariationDimension")
    record DimensionBody(@NotBlank @Size(max = 40) String name, List<@Size(max = 40) String> options,
                         @Schema(description = "Sellers may write values of their own; options are then suggestions")
                         Boolean allowCustomValues) {

        VariationDimension toDomain() {
            return new VariationDimension(name, options, allowCustomValues);
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
                          @Valid List<AttributeBody> attributes,
                          @Schema(description = "The whole new list of variation themes, when changing them")
                          @Valid @Size(max = 8) List<ThemeBody> themes,
                          @NotBlank @Size(max = 1000) String reason) {
    }
}
