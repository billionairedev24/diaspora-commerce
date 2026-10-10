package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.ProductType;

/** Category tree, product-type schemas and new-category requests. */
public interface CategoryService {

    List<CategoryNode> tree();

    ProductType productType(String code);

    List<ProductType> productTypes();

    CategoryRequest request(String parentPath, String name, String exampleProduct, String detailsNeeded);

    List<CategoryRequest> requests(CategoryRequestStatus status);

    List<CategoryRequest> myRequests();

    /** Approves a request by creating a product-type leaf under {@code parentCategoryId}. */
    CategoryRequest approve(UUID requestId, UUID parentCategoryId, String productTypeCode, String note);

    CategoryRequest reject(UUID requestId, String note);
}
