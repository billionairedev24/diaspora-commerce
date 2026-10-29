package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.AuditResponse;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.CreateNodeBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.CreateTypeBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.MoveBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.ProductTypeAdminResponse;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.ReasonBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.RenameBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.ReorderBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.TaxonomyNodeResponse;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.TaxonomyTreeResponse;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.UpdateTypeBody;
import com.afrotransact.catalog.application.TaxonomyAdminService;
import com.afrotransact.catalog.application.TaxonomyAdminService.CreateNode;
import com.afrotransact.catalog.application.TaxonomyAdminService.ProductTypeChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Taxonomy (back office)", description = "Staff create, rename, move, reorder and retire departments, "
        + "categories and product types, and edit each product type's attributes (catalog:taxonomy)")
@RestController
class TaxonomyController {

    private static final String IF_MATCH_NOTE = "If-Match: the version read with the record (412 "
            + "catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason.";

    private final TaxonomyAdminService taxonomy;

    TaxonomyController(TaxonomyAdminService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Operation(summary = "The whole tree with retired nodes, product counts and the pending-request count")
    @GetMapping("/v1/taxonomy/tree")
    TaxonomyTreeResponse taxonomyTree() {
        return TaxonomyTreeResponse.of(taxonomy.tree());
    }

    @Operation(summary = "Create a department, category or product type",
            description = "A product type uses an existing schema (productType) or defines a new one "
                    + "(newProductType). 422 for duplicate names or codes, a wrong parent level or a retired parent.")
    @PostMapping("/v1/taxonomy/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    TaxonomyNodeResponse createNode(@Valid @RequestBody CreateNodeBody body) {
        return TaxonomyNodeResponse.of(taxonomy.createNode(new CreateNode(body.level(), body.parentId(), body.name(),
                body.code(), body.productType(),
                body.newProductType() == null ? null : body.newProductType().toDomain(), body.reason())));
    }

    @Operation(summary = "Rename a node; the paths below it follow", description = IF_MATCH_NOTE)
    @PatchMapping("/v1/taxonomy/nodes/{id}")
    TaxonomyNodeResponse renameNode(@PathVariable UUID id, @Valid @RequestBody RenameBody body,
                                @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return TaxonomyNodeResponse.of(taxonomy.renameNode(id, IfMatch.expected(ifMatch), body.name(), body.reason()));
    }

    @Operation(summary = "Move a category to another department or a product type to another category",
            description = IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/nodes/{id}/move")
    TaxonomyNodeResponse moveNode(@PathVariable UUID id, @Valid @RequestBody MoveBody body,
                              @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return TaxonomyNodeResponse.of(taxonomy.moveNode(id, IfMatch.expected(ifMatch), body.parentId(),
                body.reason()));
    }

    @Operation(summary = "Retire a node (soft)",
            description = "409 catalog.taxonomy-in-use while the node or anything under it has products. A retired "
                    + "node stays on existing products but cannot be chosen for new ones. " + IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/nodes/{id}/retire")
    TaxonomyNodeResponse retireNode(@PathVariable UUID id, @Valid @RequestBody ReasonBody body,
                                @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return TaxonomyNodeResponse.of(taxonomy.retireNode(id, IfMatch.expected(ifMatch), body.reason()));
    }

    @Operation(summary = "Restore a retired node", description = IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/nodes/{id}/restore")
    TaxonomyNodeResponse restoreNode(@PathVariable UUID id, @Valid @RequestBody ReasonBody body,
                                 @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return TaxonomyNodeResponse.of(taxonomy.restoreNode(id, IfMatch.expected(ifMatch), body.reason()));
    }

    @Operation(summary = "Set the display order of the children of a node (or of the departments)",
            description = "ids is every child of the parent, retired ones included; 412 when the list changed.")
    @PostMapping("/v1/taxonomy/reorder")
    List<TaxonomyNodeResponse> reorderNodes(@Valid @RequestBody ReorderBody body) {
        return taxonomy.reorder(body.parentId(), body.ids(), body.reason()).stream()
                .map(TaxonomyNodeResponse::of).toList();
    }

    @Operation(summary = "Product-type schemas with where they are used")
    @GetMapping("/v1/taxonomy/product-types")
    List<ProductTypeAdminResponse> listTaxonomyProductTypes() {
        return taxonomy.productTypes().stream().map(ProductTypeAdminResponse::of).toList();
    }

    @Operation(summary = "One product-type schema with every attribute, including those that do not apply")
    @GetMapping("/v1/taxonomy/product-types/{code}")
    ProductTypeAdminResponse getTaxonomyProductType(@PathVariable String code) {
        return ProductTypeAdminResponse.of(taxonomy.productType(code));
    }

    @Operation(summary = "Create a product-type schema")
    @PostMapping("/v1/taxonomy/product-types")
    @ResponseStatus(HttpStatus.CREATED)
    ProductTypeAdminResponse createProductType(@Valid @RequestBody CreateTypeBody body) {
        return ProductTypeAdminResponse.of(taxonomy.createProductType(body.toDomain(), body.reason()));
    }

    @Operation(summary = "Edit a product type: label, identifier rule, traits and attributes",
            description = "Only the fields sent change. Sales-tax question (tax_class) wording and choices are owned by "
                    + "tax-returns and cannot be edited. " + IF_MATCH_NOTE)
    @PatchMapping("/v1/taxonomy/product-types/{code}")
    ProductTypeAdminResponse updateProductType(@PathVariable String code, @Valid @RequestBody UpdateTypeBody body,
                                               @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                               String ifMatch) {
        return ProductTypeAdminResponse.of(taxonomy.updateProductType(code, IfMatch.expected(ifMatch),
                new ProductTypeChange(body.label(), body.identifierRule(),
                        body.traits() == null ? null : body.traits().toDomain(),
                        body.attributes() == null ? null
                                : body.attributes().stream().map(TaxonomyDtos.AttributeBody::toDomain).toList(),
                        body.reason())));
    }

    @Operation(summary = "Audit trail of taxonomy changes, newest first")
    @GetMapping("/v1/taxonomy/audit")
    List<AuditResponse> taxonomyAudit(@RequestParam(required = false) String subject,
                              @RequestParam(defaultValue = "50") int limit) {
        return taxonomy.audit(subject, limit).stream().map(AuditResponse::of).toList();
    }
}
