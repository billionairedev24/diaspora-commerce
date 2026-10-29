package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A node of the category tree. Leaves ({@link CategoryLevel#PRODUCT_TYPE}) reference a {@link ProductType}.
 *
 * @param code          stable machine code, unique across the tree ("fashion-women-dresses")
 * @param path          human path such as "Fashion › Women › Dresses"
 * @param active        false once retired: the node stays on the products that already use it but cannot be chosen
 *                      for new ones
 * @param version       optimistic-locking version, bumped by every staff change
 * @param retiredAt     when it was retired (null while active)
 * @param retiredReason why it was retired
 */
public record CategoryNode(UUID id, UUID parentId, String code, String name, CategoryLevel level,
                           String productTypeCode, String path, int position, boolean active, long version,
                           Instant retiredAt, String retiredReason) {

    public CategoryNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(path, "path");
        if (level == CategoryLevel.PRODUCT_TYPE && productTypeCode == null) {
            throw new IllegalArgumentException("Product-type nodes need a product type code");
        }
    }

    /** A node as requests build it: code assigned on insert, first version, never retired. */
    public CategoryNode(UUID id, UUID parentId, String name, CategoryLevel level, String productTypeCode, String path,
                        int position, boolean active) {
        this(id, parentId, null, name, level, productTypeCode, path, position, active, 0, null, null);
    }

    public CategoryNode withCode(String newCode) {
        return new CategoryNode(id, parentId, newCode, name, level, productTypeCode, path, position, active, version,
                retiredAt, retiredReason);
    }

    public CategoryNode withName(String newName, String newPath) {
        return new CategoryNode(id, parentId, code, newName, level, productTypeCode, newPath, position, active,
                version, retiredAt, retiredReason);
    }

    public CategoryNode withPlacement(UUID newParentId, String newPath, int newPosition) {
        return new CategoryNode(id, newParentId, code, name, level, productTypeCode, newPath, newPosition, active,
                version, retiredAt, retiredReason);
    }

    public CategoryNode withPath(String newPath) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, newPath, position, active,
                version, retiredAt, retiredReason);
    }

    public CategoryNode withPosition(int newPosition) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, newPosition, active,
                version, retiredAt, retiredReason);
    }

    public CategoryNode retired(String reason, Instant now) {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, position, false, version,
                now, reason);
    }

    public CategoryNode restored() {
        return new CategoryNode(id, parentId, code, name, level, productTypeCode, path, position, true, version,
                null, null);
    }
}
