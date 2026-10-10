package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ProductType;

/** Read/write port for the category tree and product-type schemas. */
public interface CategoryRepository {

    List<CategoryNode> findAllActive();

    Optional<CategoryNode> findById(UUID id);

    Optional<ProductType> findProductType(String code);

    List<ProductType> findAllProductTypes();

    CategoryNode insert(CategoryNode node);
}
