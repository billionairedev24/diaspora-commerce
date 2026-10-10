package com.afrotransact.catalog.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A node of the category tree. Leaves ({@link CategoryLevel#PRODUCT_TYPE}) reference a {@link ProductType}.
 *
 * @param path human path such as "Fashion › Women › Dresses"
 */
public record CategoryNode(UUID id, UUID parentId, String name, CategoryLevel level, String productTypeCode,
                           String path, int position, boolean active) {

    public CategoryNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(path, "path");
        if (level == CategoryLevel.PRODUCT_TYPE && productTypeCode == null) {
            throw new IllegalArgumentException("Product-type nodes need a product type code");
        }
    }
}
