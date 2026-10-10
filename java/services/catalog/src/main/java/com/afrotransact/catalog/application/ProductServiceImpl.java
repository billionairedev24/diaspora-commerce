package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductDraft;
import com.afrotransact.catalog.domain.ProductDraftPolicy;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProductServiceImpl implements ProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CategoryRequestRepository requests;
    private final IdentifierIssuer ids;
    private final ProductDraftPolicy policy;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final ActorResolver actors;
    private final OwnerResolver owners;
    private final ProductEvents events;
    private final Clock clock;

    ProductServiceImpl(ProductRepository products, CategoryRepository categories, CategoryRequestRepository requests,
                       IdentifierIssuer ids, ProductDraftPolicy policy, PermissionChecker permissions,
                       ResourceAccessChecker access, ActorResolver actors, OwnerResolver owners,
                       ProductEvents events, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.requests = requests;
        this.ids = ids;
        this.policy = policy;
        this.permissions = permissions;
        this.access = access;
        this.actors = actors;
        this.owners = owners;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Product create(CreateProductCommand cmd) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_MANAGE_PRODUCTS, owner.orgId());
        Placement placement = placement(cmd, owner);

        ProductDraft draft = new ProductDraft(placement.type(), cmd.title(), cmd.attributes(), cmd.images(),
                cmd.variationTheme(), cmd.variants());
        policy.validate(draft);
        ensureNoDuplicates(owner, cmd.variants());

        Instant now = Instant.now(clock);
        String currency = cmd.currency() == null ? "USD" : cmd.currency().toUpperCase(Locale.ROOT);
        List<Variant> variants = new ArrayList<>();
        for (VariantDraft v : cmd.variants()) {
            String gtin = v.gtinExempt() ? null : Gtin.digits(v.gtin());
            variants.add(new Variant(UUID.randomUUID(), ids.nextAtin(), v.sellerSku().trim().toUpperCase(Locale.ROOT),
                    gtin == null || gtin.isEmpty() ? null : gtin, v.gtinExempt(), v.options(), v.priceMinor(),
                    currency, v.quantity()));
        }
        ProductStatus status = placement.request() == null
                ? ProductStatus.IN_REVIEW
                : ProductStatus.PENDING_CATEGORY_APPROVAL;
        boolean preorderEligible = cmd.preorderEligible() != null ? cmd.preorderEligible()
                : Product.preorderDefault(placement.type().code());
        Product product = products.insert(new Product(UUID.randomUUID(), ids.nextAtin(), owner,
                placement.categoryId(), placement.request() == null ? null : placement.request().id(),
                placement.type().code(), cmd.title().trim(), cmd.attributes(), cmd.images(), cmd.variationTheme(),
                variants, status, null, now, now, 0, preorderEligible, Boolean.TRUE.equals(cmd.shipsNow())));
        publish(CatalogEvents.PRODUCT_CREATED, product);
        return product;
    }

    @Override
    @Transactional(readOnly = true)
    public Product get(UUID id) {
        Product p = products.findById(id).orElseThrow(() -> notFound(id.toString()));
        ensureVisible(p);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public Product getByAtin(String atin) {
        Product p = products.findByAtin(new Atin(atin.toUpperCase(Locale.ROOT))).orElseThrow(() -> notFound(atin));
        ensureVisible(p);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public VariantLookup variant(String variantAtin) {
        Actor actor = actors.current();
        String permission = actor.can(CatalogPermissions.READ) ? CatalogPermissions.READ : CatalogPermissions.WRITE;
        permissions.require(permission);
        Atin wanted = parseAtin(variantAtin);
        Product p = products.findByVariantAtin(wanted).orElseThrow(() -> variantNotFound(variantAtin));
        if (CatalogPermissions.WRITE.equals(permission)) {
            ensureOwnerOrStaff(actor, p);
        }
        access.requireOwned(permission, CatalogRelations.CAN_VIEW, CatalogRelations.product(p.id()),
                p.owner().orgId());
        Variant variant = p.variants().stream().filter(v -> v.atin().equals(wanted)).findFirst()
                .orElseThrow(() -> variantNotFound(variantAtin));
        return new VariantLookup(p, variant);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<Product> list(String ownerOrgId, ProductStatus status, String text, int page, int size) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        String org = isStaff(actor) ? blankToNull(ownerOrgId) : owners.ownerFor(actor).orgId();
        if (org == null) {
            access.require(CatalogPermissions.WRITE, CatalogRelations.PLATFORM_VIEW_ALL, FgaObject.PLATFORM);
        } else {
            access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, org);
        }
        ProductRepository.ProductPage result = products.search(new ProductRepository.ProductQuery(org, status, text,
                Math.max(page, 0), Math.clamp(size, 1, 100)));
        return new PageView<>(result.items(), result.total(), result.page(), result.size());
    }

    @Override
    @Transactional
    public Product approve(UUID id) {
        permissions.require(CatalogPermissions.MODERATE);
        return transition(id, p -> p.approve(Instant.now(clock)), CatalogEvents.PRODUCT_APPROVED);
    }

    @Override
    @Transactional
    public Product reject(UUID id, String note) {
        permissions.require(CatalogPermissions.MODERATE);
        if (note == null || note.isBlank()) {
            throw new CatalogRuleViolation("A rejection note is required", Map.of("note", "Required"));
        }
        return transition(id, p -> p.reject(note.trim(), Instant.now(clock)), CatalogEvents.PRODUCT_REJECTED);
    }

    @Override
    @Transactional
    public Product pause(UUID id) {
        requireEditable(id);
        return transition(id, p -> p.pause(Instant.now(clock)), CatalogEvents.PRODUCT_PAUSED);
    }

    @Override
    @Transactional
    public Product resume(UUID id) {
        requireEditable(id);
        return transition(id, p -> p.resume(Instant.now(clock)), CatalogEvents.PRODUCT_RESUMED);
    }

    @Override
    @Transactional
    public Product setPreorderFlags(UUID id, boolean preorderEligible, boolean shipsNow) {
        requireEditable(id);
        return transition(id, p -> p.withPreorderFlags(preorderEligible, shipsNow, Instant.now(clock)),
                CatalogEvents.PRODUCT_UPDATED);
    }

    private Product transition(UUID id, UnaryOperator<Product> change, String eventType) {
        Product current = products.findById(id).orElseThrow(() -> notFound(id.toString()));
        Product updated = products.update(change.apply(current));
        publish(eventType, updated);
        return updated;
    }

    private Placement placement(CreateProductCommand cmd, ListingOwner owner) {
        if ((cmd.categoryId() == null) == (cmd.categoryRequestId() == null)) {
            throw new CatalogRuleViolation("Choose a product type, or request a new category",
                    Map.of("category", "Required"));
        }
        if (cmd.categoryId() != null) {
            CategoryNode node = categories.findById(cmd.categoryId())
                    .filter(CategoryNode::active)
                    .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Category not found"));
            if (node.level() != CategoryLevel.PRODUCT_TYPE) {
                throw new CatalogRuleViolation("Pick a product type (third level)", Map.of("category", "Too broad"));
            }
            return new Placement(node.id(), null, type(node.productTypeCode()));
        }
        CategoryRequest request = requests.findById(cmd.categoryRequestId())
                .orElseThrow(() -> new NotFoundException("catalog.category-request-not-found",
                        "Category request not found"));
        if (!owner.orgId().equals(request.requestedByOrg())) {
            throw new CatalogRuleViolation("You can only build products against your own category requests",
                    Map.of("category", "Not your request"));
        }
        if (request.status() == CategoryRequestStatus.REJECTED) {
            throw new CatalogRuleViolation("That category request was rejected", Map.of("category", "Rejected"));
        }
        return new Placement(null, request, type(ProductType.CUSTOM));
    }

    private ProductType type(String code) {
        return categories.findProductType(code)
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type"));
    }

    private void ensureNoDuplicates(ListingOwner owner, List<VariantDraft> variants) {
        for (VariantDraft v : variants) {
            if (products.sellerSkuTaken(owner.orgId(), v.sellerSku().trim().toUpperCase(Locale.ROOT))) {
                throw new ConflictException("catalog.sku-taken",
                        "SKU " + v.sellerSku() + " is already used by another of your products");
            }
            String gtin = Gtin.digits(v.gtin());
            if (!v.gtinExempt() && !gtin.isEmpty()) {
                products.findByVariantGtin(gtin).ifPresent(existing -> {
                    throw new ConflictException("catalog.gtin-exists",
                            "GTIN " + gtin + " already has a product page (" + existing.atin()
                                    + "). Add an offer to it instead of creating a duplicate.");
                });
            }
        }
    }

    private void ensureVisible(Product p) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ensureOwnerOrStaff(actor, p);
        access.requireOwned(CatalogPermissions.WRITE, CatalogRelations.CAN_VIEW, CatalogRelations.product(p.id()),
                p.owner().orgId());
    }

    /** Owner or staff by the token (404 otherwise), then the relationship: catalog role of the owner, market staff. */
    private void requireEditable(UUID id) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        Product p = products.findById(id).orElseThrow(() -> notFound(id.toString()));
        ensureOwnerOrStaff(actor, p);
        access.requireOwned(CatalogPermissions.WRITE, CatalogRelations.CAN_EDIT, CatalogRelations.product(p.id()),
                p.owner().orgId());
    }

    private static Atin parseAtin(String raw) {
        try {
            return new Atin(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw variantNotFound(raw);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static NotFoundException variantNotFound(String atin) {
        return new NotFoundException("catalog.variant-not-found", "Variant " + atin + " not found");
    }

    private void ensureOwnerOrStaff(Actor actor, Product p) {
        if (!isStaff(actor) && !actor.orgId().map(p.owner().orgId()::equals).orElse(false)) {
            throw notFound(p.id().toString());
        }
    }

    private static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }

    private void publish(String type, Product p) {
        events.publish(type, p);
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("catalog.product-not-found", "Product " + id + " not found");
    }

    private record Placement(UUID categoryId, CategoryRequest request, ProductType type) {
    }
}
