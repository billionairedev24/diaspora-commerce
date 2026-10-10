package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categories;
    private final CategoryRequestRepository requests;
    private final ProductRepository products;
    private final IdentifierIssuer ids;
    private final PermissionChecker permissions;
    private final OwnerResolver owners;
    private final DomainEventPublisher events;
    private final Clock clock;

    CategoryServiceImpl(CategoryRepository categories, CategoryRequestRepository requests, ProductRepository products,
                        IdentifierIssuer ids, PermissionChecker permissions, OwnerResolver owners,
                        DomainEventPublisher events, Clock clock) {
        this.categories = categories;
        this.requests = requests;
        this.products = products;
        this.ids = ids;
        this.permissions = permissions;
        this.owners = owners;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryNode> tree() {
        return categories.findAllActive();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductType productType(String code) {
        return categories.findProductType(code)
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type " + code));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductType> productTypes() {
        return categories.findAllProductTypes();
    }

    @Override
    @Transactional
    public CategoryRequest request(String parentPath, String name, String exampleProduct, String detailsNeeded) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        String org = owners.ownerFor(actor).orgId();
        CategoryRequest created = requests.insert(CategoryRequest.open(UUID.randomUUID(),
                ids.nextCategoryRequestReference(), org, parentPath, name, exampleProduct, detailsNeeded,
                Instant.now(clock)));
        publish(CatalogEvents.CATEGORY_REQUESTED, created);
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRequest> requests(CategoryRequestStatus status) {
        permissions.require(CatalogPermissions.TAXONOMY);
        return requests.findByStatus(status == null ? CategoryRequestStatus.PENDING_REVIEW : status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRequest> myRequests() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        return requests.findByOrg(owners.ownerFor(actor).orgId());
    }

    @Override
    @Transactional
    public CategoryRequest approve(UUID requestId, UUID parentCategoryId, String productTypeCode, String note) {
        permissions.require(CatalogPermissions.TAXONOMY);
        CategoryRequest request = find(requestId);
        CategoryNode parent = categories.findById(parentCategoryId)
                .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Parent category not found"));
        if (parent.level() != CategoryLevel.CATEGORY) {
            throw new CatalogRuleViolation("New product types go under a second-level category",
                    Map.of("parentCategoryId", "Pick a category, not a department or product type"));
        }
        ProductType type = productType(productTypeCode);
        Instant now = Instant.now(clock);
        CategoryNode leaf = categories.insert(new CategoryNode(UUID.randomUUID(), parent.id(), request.name(),
                CategoryLevel.PRODUCT_TYPE, type.code(), parent.path() + " › " + request.name(), 999, true));
        CategoryRequest approved = requests.update(request.approve(leaf.id(), note, now));
        for (Product waiting : products.findByCategoryRequest(approved.id())) {
            Product moved = products.update(waiting.categoryApproved(leaf.id(), type.code(), now));
            events.publish(CatalogEvents.PRODUCT_CREATED, CatalogEvents.AGGREGATE_PRODUCT, moved.id().toString(),
                    ProductEventPayload.of(moved));
        }
        publish(CatalogEvents.CATEGORY_APPROVED, approved);
        return approved;
    }

    @Override
    @Transactional
    public CategoryRequest reject(UUID requestId, String note) {
        permissions.require(CatalogPermissions.TAXONOMY);
        Instant now = Instant.now(clock);
        CategoryRequest rejected = requests.update(find(requestId).reject(note, now));
        for (Product waiting : products.findByCategoryRequest(rejected.id())) {
            products.update(waiting.reject("Requested category was not approved: " + note, now));
        }
        publish(CatalogEvents.CATEGORY_REJECTED, rejected);
        return rejected;
    }

    private CategoryRequest find(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException("catalog.category-request-not-found",
                "Category request not found"));
    }

    private void publish(String type, CategoryRequest r) {
        events.publish(type, CatalogEvents.AGGREGATE_CATEGORY_REQUEST, r.id().toString(),
                Map.of("id", r.id().toString(), "reference", r.reference(), "orgId", r.requestedByOrg(),
                        "path", r.fullPath(), "status", r.status().name()));
    }
}
