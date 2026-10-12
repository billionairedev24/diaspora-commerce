package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProductServiceImpl implements ProductService {

    private final ProductRepository products;
    private final ProductWriter writer;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final ActorResolver actors;
    private final OwnerResolver owners;
    private final ProductEvents events;
    private final Clock clock;

    ProductServiceImpl(ProductRepository products, ProductWriter writer, PermissionChecker permissions,
                       ResourceAccessChecker access, ActorResolver actors, OwnerResolver owners,
                       ProductEvents events, Clock clock) {
        this.products = products;
        this.writer = writer;
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
        return writer.create(owner, cmd);
    }

    @Override
    @Transactional
    public Product update(UUID id, UpdateProductCommand cmd) {
        Product current = requireEditable(id);
        return writer.update(current, cmd);
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

    private void ensureVisible(Product p) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ensureOwnerOrStaff(actor, p);
        access.requireOwned(CatalogPermissions.WRITE, CatalogRelations.CAN_VIEW, CatalogRelations.product(p.id()),
                p.owner().orgId());
    }

    /** Owner or staff by the token (404 otherwise), then the relationship: catalog role of the owner, market staff. */
    private Product requireEditable(UUID id) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        Product p = products.findById(id).orElseThrow(() -> notFound(id.toString()));
        ensureOwnerOrStaff(actor, p);
        access.requireOwned(CatalogPermissions.WRITE, CatalogRelations.CAN_EDIT, CatalogRelations.product(p.id()),
                p.owner().orgId());
        return p;
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
}
