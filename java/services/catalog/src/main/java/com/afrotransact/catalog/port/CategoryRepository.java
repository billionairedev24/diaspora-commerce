package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.StoredProductType;

/** Read/write port for the category tree and product-type schemas. */
public interface CategoryRepository {

    /** The tree shoppers and sellers see: active nodes only. */
    List<CategoryNode> findAllActive();

    /** Every node including retired ones (staff, and consumers resolving a product's category). */
    List<CategoryNode> findAll();

    Optional<CategoryNode> findById(UUID id);

    /** The children of a node (null parent: the departments), retired ones included, in display order. */
    List<CategoryNode> findChildren(UUID parentId);

    Optional<CategoryNode> findByCode(String code);

    /** The node whose human path is this one (case-insensitive). */
    Optional<CategoryNode> findByPath(String path);

    Optional<ProductType> findProductType(String code);

    List<ProductType> findAllProductTypes();

    Optional<StoredProductType> findStoredProductType(String code);

    List<StoredProductType> findAllStoredProductTypes();

    CategoryNode insert(CategoryNode node);

    /**
     * Writes the node's name, placement, path, position and retirement when {@code node.version()} still matches and
     * returns the stored copy with version + 1.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when the stored version is different
     */
    CategoryNode update(CategoryNode node);

    /** Rewrites the path of a descendant after an ancestor was renamed or moved (no version change). */
    void updatePath(UUID id, String path);

    /** Saved product-form drafts keep the category path as text: rewrites the ones under {@code oldPath}. */
    void rewriteDraftPaths(String oldPath, String newPath);

    StoredProductType insertProductType(ProductType type);

    /**
     * Replaces the schema when {@code expectedVersion} still matches; returns the stored copy with version + 1.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when the stored version is different
     */
    StoredProductType updateProductType(ProductType type, long expectedVersion);
}
