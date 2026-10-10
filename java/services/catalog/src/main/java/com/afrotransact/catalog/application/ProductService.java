package com.afrotransact.catalog.application;

import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;

/** Product use cases: create (submit for review), read, moderate. */
public interface ProductService {

    Product create(CreateProductCommand command);

    Product get(UUID id);

    Product getByAtin(String atin);

    /** Products of the caller's organisation (staff see all and may filter by org). */
    PageView<Product> list(String ownerOrgId, ProductStatus status, String text, int page, int size);

    Product approve(UUID id);

    Product reject(UUID id, String note);

    Product pause(UUID id);

    Product resume(UUID id);
}
