package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.TaxonomyAdminService.CreateNode;
import com.afrotransact.catalog.application.TaxonomyAdminService.NewProductType;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.AuditSubject;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyRules;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
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
    private final ProductEvents productEvents;
    private final TaxonomyAdminService taxonomy;
    private final TaxonomyAuditRepository audit;
    private final Clock clock;

    CategoryServiceImpl(CategoryRepository categories, CategoryRequestRepository requests, ProductRepository products,
                        IdentifierIssuer ids, PermissionChecker permissions, OwnerResolver owners,
                        DomainEventPublisher events, ProductEvents productEvents, TaxonomyAdminService taxonomy,
                        TaxonomyAuditRepository audit, Clock clock) {
        this.categories = categories;
        this.requests = requests;
        this.products = products;
        this.ids = ids;
        this.permissions = permissions;
        this.owners = owners;
        this.events = events;
        this.productEvents = productEvents;
        this.taxonomy = taxonomy;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryNode> tree() {
        return categories.findAllActive();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryNode> tree(boolean includeRetired) {
        return includeRetired ? categories.findAll() : categories.findAllActive();
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
    public List<QueueItem> requests(CategoryRequestStatus status) {
        permissions.require(CatalogPermissions.TAXONOMY);
        List<CategoryRequest> found = requests.findByStatus(status == null ? CategoryRequestStatus.PENDING_REVIEW : status);
        Map<UUID, Long> waiting = products.countByCategoryRequest(found.stream().map(CategoryRequest::id).toList());
        return found.stream().sorted(Comparator.comparing(CategoryRequest::createdAt))
                .map(r -> new QueueItem(r, waiting.getOrDefault(r.id(), 0L))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RequestDetail requestDetail(UUID requestId) {
        permissions.require(CatalogPermissions.TAXONOMY);
        CategoryRequest request = find(requestId);
        CategoryNode suggested = categories.findByPath(request.parentPath()).filter(CategoryNode::active)
                .filter(n -> n.level() != CategoryLevel.PRODUCT_TYPE).orElse(null);
        return new RequestDetail(request, products.findByCategoryRequest(requestId), suggested);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRequest> myRequests() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        return requests.findByOrg(owners.ownerFor(actor).orgId());
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalPlan planApproval(UUID requestId, ApprovalChoice choice) {
        permissions.require(CatalogPermissions.TAXONOMY);
        return plan(find(requestId), choice).plan();
    }

    @Override
    @Transactional
    public CategoryRequest approve(UUID requestId, ApprovalChoice choice, String note) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        CategoryRequest request = find(requestId);
        Plan plan = plan(request, choice);
        String reason = "Category request " + request.reference() + " approved"
                + (note == null || note.isBlank() ? "" : ": " + note.trim());
        Instant now = Instant.now(clock);

        CategoryNode category = plan.parent();
        if (plan.parent().level() == CategoryLevel.DEPARTMENT) {
            category = plan.existingCategory() != null ? plan.existingCategory()
                    : taxonomy.createNode(new CreateNode(CategoryLevel.CATEGORY, plan.parent().id(),
                            plan.categoryName(), null, null, null, reason)).node();
        }
        NewProductType newType = choice.newProductType() == null ? null : withLabel(choice.newProductType(), request.name());
        CategoryNode leaf = taxonomy.createNode(new CreateNode(CategoryLevel.PRODUCT_TYPE, category.id(),
                request.name(), null, choice.productTypeCode(), newType, reason)).node();

        CategoryRequest approved = requests.update(request.approve(leaf.id(), note, now));
        for (Product waiting : products.findByCategoryRequest(approved.id())) {
            Product moved = products.update(waiting.categoryApproved(leaf.id(), leaf.productTypeCode(), now));
            productEvents.publish(CatalogEvents.PRODUCT_CREATED, moved);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("createdPath", leaf.path());
        details.put("productType", leaf.productTypeCode());
        details.put("productsMoved", plan.plan().productsMoving());
        recordDecision(actor, "REQUEST_APPROVED", approved, reason, details);
        publish(CatalogEvents.CATEGORY_APPROVED, approved);
        return approved;
    }

    @Override
    @Transactional
    public CategoryRequest reject(UUID requestId, String note) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        Instant now = Instant.now(clock);
        CategoryRequest rejected = requests.update(find(requestId).reject(note, now));
        List<Product> waiting = products.findByCategoryRequest(rejected.id());
        for (Product product : waiting) {
            products.update(product.reject("Requested category was not approved: " + note, now));
        }
        recordDecision(actor, "REQUEST_REJECTED", rejected, TaxonomyRules.reason(note),
                Map.of("productsRejected", waiting.size()));
        publish(CatalogEvents.CATEGORY_REJECTED, rejected);
        return rejected;
    }

    private Plan plan(CategoryRequest request, ApprovalChoice choice) {
        if (request.status() != CategoryRequestStatus.PENDING_REVIEW) {
            throw new ConflictException("catalog.invalid-transition",
                    "Category request " + request.reference() + " is already " + request.status());
        }
        if (choice == null || choice.parentCategoryId() == null) {
            throw violation("parentCategoryId", "Choose where it goes");
        }
        CategoryNode parent = categories.findById(choice.parentCategoryId()).orElseThrow(
                () -> new NotFoundException("catalog.category-not-found", "Parent category not found"));
        if (parent.level() == CategoryLevel.PRODUCT_TYPE) {
            throw violation("parentCategoryId", "Pick a department or a category, not a product type");
        }
        if (!parent.active()) {
            throw violation("parentCategoryId", parent.name() + " is retired");
        }
        List<PlannedNode> nodes = new ArrayList<>();
        String categoryName = null;
        CategoryNode existingCategory = null;
        String leafParentPath = parent.path();
        if (parent.level() == CategoryLevel.DEPARTMENT) {
            categoryName = choice.categoryName() == null || choice.categoryName().isBlank() ? request.name()
                    : TaxonomyRules.name(choice.categoryName(), "categoryName");
            String wanted = categoryName.toLowerCase(Locale.ROOT);
            existingCategory = categories.findChildren(parent.id()).stream()
                    .filter(c -> c.name().toLowerCase(Locale.ROOT).equals(wanted)).findFirst().orElse(null);
            if (existingCategory != null && !existingCategory.active()) {
                throw violation("categoryName", existingCategory.name() + " exists but is retired; restore it first");
            }
            leafParentPath = TaxonomyRules.pathOf(parent.path(), existingCategory == null ? categoryName
                    : existingCategory.name());
            nodes.add(new PlannedNode(CategoryLevel.CATEGORY, categoryName, leafParentPath, existingCategory == null));
        } else {
            existingCategory = parent;
        }
        String leafName = TaxonomyRules.name(request.name(), "name");
        if (existingCategory != null && categories.findChildren(existingCategory.id()).stream()
                .anyMatch(c -> c.name().equalsIgnoreCase(leafName))) {
            throw violation("name", "There is already a " + leafName + " in " + existingCategory.path());
        }
        nodes.add(new PlannedNode(CategoryLevel.PRODUCT_TYPE, leafName, TaxonomyRules.pathOf(leafParentPath, leafName),
                true));
        PlannedType type = plannedType(choice, leafName);
        long moving = products.countByCategoryRequest(List.of(request.id())).getOrDefault(request.id(), 0L);
        return new Plan(new ApprovalPlan(List.copyOf(nodes), type, moving), parent, categoryName,
                parent.level() == CategoryLevel.DEPARTMENT ? existingCategory : null);
    }

    private PlannedType plannedType(ApprovalChoice choice, String leafName) {
        boolean existing = choice.productTypeCode() != null && !choice.productTypeCode().isBlank();
        if (existing == (choice.newProductType() != null)) {
            throw violation("productType", "Choose an existing product type or define a new one");
        }
        if (existing) {
            String code = choice.productTypeCode().trim();
            ProductType type = categories.findProductType(code).filter(t -> !ProductType.CUSTOM.equals(t.code()))
                    .orElseThrow(() -> violation("productType", "Unknown product type " + code));
            return new PlannedType(type.code(), type.label(), false, type.applicableAttributes().size());
        }
        NewProductType n = withLabel(choice.newProductType(), leafName);
        String label = TaxonomyRules.name(n.label(), "label");
        String code = n.code() == null || n.code().isBlank() ? TaxonomyRules.typeCodeFrom(label)
                : TaxonomyRules.typeCode(n.code());
        return new PlannedType(code, label, true, n.attributes() == null ? 0 : n.attributes().size());
    }

    private static NewProductType withLabel(NewProductType n, String fallbackLabel) {
        if (n.label() != null && !n.label().isBlank()) {
            return n;
        }
        return new NewProductType(n.code(), fallbackLabel, n.identifierRule(), n.traits(), n.attributes(),
                n.basedOn());
    }

    private CategoryRequest find(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException("catalog.category-request-not-found",
                "Category request not found"));
    }

    private void recordDecision(Actor actor, String action, CategoryRequest r, String reason,
                                Map<String, Object> details) {
        audit.append(new TaxonomyAuditEntry(UUID.randomUUID(), Instant.now(clock), actor.subject(), action,
                AuditSubject.REQUEST, r.id().toString(), r.reference() + " · " + r.fullPath(), reason, details));
    }

    private static CatalogRuleViolation violation(String field, String message) {
        return new CatalogRuleViolation(message, Map.of(field, message));
    }

    private void publish(String type, CategoryRequest r) {
        events.publish(type, CatalogEvents.AGGREGATE_CATEGORY_REQUEST, r.id().toString(),
                Map.of("id", r.id().toString(), "reference", r.reference(), "orgId", r.requestedByOrg(),
                        "path", r.fullPath(), "status", r.status().name()));
    }

    private record Plan(ApprovalPlan plan, CategoryNode parent, String categoryName, CategoryNode existingCategory) {
    }
}
