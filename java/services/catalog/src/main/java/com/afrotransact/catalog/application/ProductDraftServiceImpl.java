package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.SavedDraft;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProductDraftServiceImpl implements ProductDraftService {

    private static final int LIST_LIMIT = 100;

    private final ProductDraftRepository drafts;
    private final ProductRepository products;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;
    private final Clock clock;

    ProductDraftServiceImpl(ProductDraftRepository drafts, ProductRepository products, PermissionChecker permissions,
                            ResourceAccessChecker access, OwnerResolver owners, Clock clock) {
        this.drafts = drafts;
        this.products = products;
        this.permissions = permissions;
        this.access = access;
        this.owners = owners;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedDraft> list() {
        return drafts.findByOwnerOrg(owner().orgId(), LIST_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public SavedDraft get(UUID id) {
        return owned(id, owner());
    }

    @Override
    @Transactional
    public SavedDraft create(UUID productId, String title, String categoryPath, String payload) {
        ListingOwner owner = owner();
        checkPayload(payload);
        Instant now = Instant.now(clock);
        if (productId != null) {
            Product p = products.findById(productId).filter(x -> x.owner().orgId().equals(owner.orgId()))
                    .orElseThrow(() -> new NotFoundException("catalog.product-not-found", "Product " + productId + " not found"));
            UUID existing = drafts.findByOwnerOrg(owner.orgId(), LIST_LIMIT).stream()
                    .filter(d -> p.id().equals(d.productId())).map(SavedDraft::id).findFirst().orElse(null);
            if (existing != null) {
                SavedDraft d = drafts.findById(existing).orElseThrow();
                return drafts.update(d.saved(title, categoryPath, payload, now));
            }
        }
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        return drafts.insert(new SavedDraft(UUID.randomUUID(), owner.orgId(), actor.subject(), productId, title,
                categoryPath, payload, now, now, 0));
    }

    @Override
    @Transactional
    public SavedDraft save(UUID id, long version, String title, String categoryPath, String payload) {
        checkPayload(payload);
        SavedDraft d = owned(id, owner());
        if (d.version() != version) {
            throw new ConflictException("catalog.draft-changed",
                    "This draft was saved from another tab or device; reload it to keep editing");
        }
        return drafts.update(d.saved(title, categoryPath, payload, Instant.now(clock)));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        drafts.delete(owned(id, owner()).id());
    }

    private ListingOwner owner() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_MANAGE_PRODUCTS, owner.orgId());
        return owner;
    }

    private SavedDraft owned(UUID id, ListingOwner owner) {
        return drafts.findById(id).filter(d -> d.ownerOrg().equals(owner.orgId()))
                .orElseThrow(() -> new NotFoundException("catalog.draft-not-found", "Draft " + id + " not found"));
    }

    private static void checkPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new CatalogRuleViolation("The draft is empty", Map.of("payload", "Required"));
        }
        if (payload.length() > SavedDraft.MAX_PAYLOAD_CHARS) {
            throw new CatalogRuleViolation("The draft is too large", Map.of("payload", "At most 256 KB"));
        }
    }
}
