package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.taxonomy.StoredProductType;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;

/**
 * Staff administration of the taxonomy (permission {@code catalog:taxonomy}): departments, categories and product
 * types, and the schema of each product type. Every change carries a reason, is audited, bumps the record's version
 * (edits name the version they loaded; a stale one is refused) and publishes {@code catalog.taxonomy.changed}.
 * Retiring is soft; a node that products still use cannot be retired.
 */
public interface TaxonomyAdminService {

    /** Every node including retired ones, with product counts, and the number of pending category requests. */
    TaxonomyTree tree();

    CategoryNodeView createNode(CreateNode command);

    /** Renames a node; the paths of everything under it follow. */
    CategoryNodeView renameNode(UUID id, Long expectedVersion, String name, String reason);

    /** Moves a category to another department or a product type to another category (products follow). */
    CategoryNodeView moveNode(UUID id, Long expectedVersion, UUID newParentId, String reason);

    /** Retires a node and what is under it; refused while products use any of it. */
    CategoryNodeView retireNode(UUID id, Long expectedVersion, String reason);

    /** Brings a retired node back (and what was retired with it). */
    CategoryNodeView restoreNode(UUID id, Long expectedVersion, String reason);

    /** Sets the display order of the children of {@code parentId} (null: the departments). */
    List<CategoryNodeView> reorder(UUID parentId, List<UUID> orderedIds, String reason);

    List<ProductTypeView> productTypes();

    ProductTypeView productType(String code);

    ProductTypeView createProductType(NewProductType command, String reason);

    ProductTypeView updateProductType(String code, Long expectedVersion, ProductTypeChange change);

    /** Staff changes, newest first; {@code subjectId} null for all. */
    List<TaxonomyAuditEntry> audit(String subjectId, int limit);

    record TaxonomyTree(List<CategoryNodeView> nodes, long pendingRequests) {
    }

    /**
     * A node with what depends on it.
     *
     * @param productCount        products directly on the node (rejected ones excluded)
     * @param subtreeProductCount products on the node and everything under it
     */
    record CategoryNodeView(CategoryNode node, String productTypeLabel, long productCount, long subtreeProductCount,
                            int childCount) {
    }

    /**
     * A product-type schema with where it is used.
     *
     * @param productCount products of this type (any status)
     * @param usedBy       the leaf nodes that use it
     */
    record ProductTypeView(StoredProductType stored, long productCount, List<TypeUse> usedBy) {
    }

    record TypeUse(UUID nodeId, String path, boolean active) {
    }

    /**
     * A new product type. Variation themes are copied from {@code basedOn} (default: the placeholder type).
     *
     * @param code optional; derived from the label when blank
     */
    record NewProductType(String code, String label, IdentifierRule identifierRule, ProductTypeTraits traits,
                          List<AttributeDefinition> attributes, String basedOn) {
    }

    /**
     * A new node. A product-type node uses an existing schema ({@code productTypeCode}) or defines a new one.
     *
     * @param parentId optional for departments, required otherwise
     * @param code     optional; derived from the path when blank
     */
    record CreateNode(CategoryLevel level, UUID parentId, String name, String code, String productTypeCode,
                      NewProductType newProductType, String reason) {
    }

    /** Fields left null stay as they are; at least one must change. */
    record ProductTypeChange(String label, IdentifierRule identifierRule, ProductTypeTraits traits,
                             List<AttributeDefinition> attributes, String reason) {
    }
}
