package com.afrotransact.catalog.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.TaxonomyAdminService.NewProductType;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductType;

/** Category tree, product-type schemas and new-category requests. */
public interface CategoryService {

    /** The active tree (what shoppers and sellers choose from). */
    List<CategoryNode> tree();

    /**
     * The tree, optionally with retired nodes: consumers that resolve the category of an existing product (search
     * indexing, delivery windows) need those too.
     */
    List<CategoryNode> tree(boolean includeRetired);

    ProductType productType(String code);

    List<ProductType> productTypes();

    CategoryRequest request(String parentPath, String name, String exampleProduct, String detailsNeeded);

    /** The review queue (catalog:taxonomy), oldest first, with the products waiting on each request. */
    List<QueueItem> requests(CategoryRequestStatus status);

    /** One request with the products waiting on it and the node its requested path points to (catalog:taxonomy). */
    RequestDetail requestDetail(UUID requestId);

    List<CategoryRequest> myRequests();

    /** What approving would create, without creating it (catalog:taxonomy). */
    ApprovalPlan planApproval(UUID requestId, ApprovalChoice choice);

    /**
     * Approves a request by creating its category (when it goes under a department) and its product-type leaf, with
     * an existing product-type schema or a new one; products waiting on it move into listing review.
     */
    CategoryRequest approve(UUID requestId, ApprovalChoice choice, String note);

    CategoryRequest reject(UUID requestId, String note);

    /**
     * Where and how an approved request is created.
     *
     * @param parentCategoryId a department (a category is created for it) or a category (the leaf goes in it)
     * @param categoryName     the category created under a department; defaults to the requested name
     * @param productTypeCode  an existing schema for the leaf, or null when {@code newProductType} defines one
     */
    record ApprovalChoice(UUID parentCategoryId, String categoryName, String productTypeCode,
                          NewProductType newProductType) {
    }

    record QueueItem(CategoryRequest request, long productsWaiting) {
    }

    record RequestDetail(CategoryRequest request, List<Product> waitingProducts, CategoryNode suggestedParent) {
    }

    /**
     * What approving creates.
     *
     * @param nodes          the categories and product types that will be created or reused, top down
     * @param productsMoving products leaving "pending category" for listing review
     */
    record ApprovalPlan(List<PlannedNode> nodes, PlannedType productType, long productsMoving) {
    }

    record PlannedNode(CategoryLevel level, String name, String path, boolean created) {
    }

    record PlannedType(String code, String label, boolean created, int attributeCount) {
    }
}
