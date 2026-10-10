package com.afrotransact.retail.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.Gtin;
import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.RetailStateConflict;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierItem;
import com.afrotransact.retail.port.CatalogClient;
import com.afrotransact.retail.port.CatalogVariant;
import com.afrotransact.retail.port.ItemRepository;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.ReferenceIssuer;
import com.afrotransact.retail.port.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ItemServiceImpl implements ItemService {

    static final String PLATFORM_OWNER = "PLATFORM";

    private final ItemRepository items;
    private final SupplierRepository suppliers;
    private final CatalogClient catalog;
    private final ReferenceIssuer refs;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final RetailSettings settings;
    private final Clock clock;

    ItemServiceImpl(ItemRepository items, SupplierRepository suppliers, CatalogClient catalog, ReferenceIssuer refs,
                    PermissionChecker permissions, DomainEventPublisher events, RetailSettings settings,
                    Clock clock) {
        this.items = items;
        this.suppliers = suppliers;
        this.catalog = catalog;
        this.refs = refs;
        this.permissions = permissions;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RetailItem create(ItemCommand c) {
        permissions.require(RetailPermissions.ITEMS_WRITE);
        Supplier primary = supplier(c.primarySupplierId());
        checkSuppliers(c);
        String atin = c.atin() == null || c.atin().isBlank() ? null : verifiedAtin(c.atin().trim(), null);
        long ref = refs.nextItemReference();
        Instant now = Instant.now(clock);
        RetailItem item = new RetailItem(UUID.randomUUID(), RetailItem.skuFor(c.name(), ref), ref,
                Gtin.issue(settings.gs1CompanyPrefix(), ref), c.name() == null ? null : c.name().trim(),
                c.category(), atin, c.primarySupplierId(), c.supplierItems(),
                c.originCountry() == null || c.originCountry().isBlank() ? primary.country() : c.originCountry(),
                c.hsCode(), c.dutyRatePercent(), c.shelfLifeDays(), c.storage(), c.reorderPoint(), c.packSize(),
                c.innerPack(), c.casePack(), c.unitWeightGrams(), c.standardCostMinor(), c.priceMinor(),
                settings.baseCurrency(), ListingStatus.DRAFT, now, now, 0);
        RetailItem created = items.insert(item);
        publish(RetailEvents.ITEM_CREATED, created);
        return created;
    }

    @Override
    @Transactional
    public RetailItem update(UUID id, ItemCommand c, Long expectedVersion) {
        permissions.require(RetailPermissions.ITEMS_WRITE);
        RetailItem current = find(id);
        checkVersion(current, expectedVersion);
        supplier(c.primarySupplierId());
        checkSuppliers(c);
        RetailItem updated = items.update(current.revise(c.name() == null ? null : c.name().trim(), c.category(),
                c.primarySupplierId(), c.supplierItems(), c.hsCode(), c.dutyRatePercent(), c.shelfLifeDays(),
                c.storage(), c.reorderPoint(), c.packSize(), c.innerPack(), c.casePack(), c.unitWeightGrams(),
                c.standardCostMinor(), c.priceMinor(), Instant.now(clock)));
        publish(RetailEvents.ITEM_UPDATED, updated);
        return updated;
    }

    @Override
    @Transactional
    public RetailItem linkAtin(UUID id, String atin, Long expectedVersion) {
        permissions.require(RetailPermissions.ITEMS_WRITE);
        RetailItem current = find(id);
        checkVersion(current, expectedVersion);
        String verified = verifiedAtin(atin == null ? null : atin.trim(), id);
        RetailItem updated = items.update(current.linkAtin(verified, Instant.now(clock)));
        publish(RetailEvents.ITEM_UPDATED, updated);
        return updated;
    }

    @Override
    @Transactional
    public RetailItem changeListing(UUID id, ListingStatus target, Long expectedVersion) {
        permissions.require(RetailPermissions.ITEMS_WRITE);
        RetailItem current = find(id);
        checkVersion(current, expectedVersion);
        RetailItem updated = items.update(current.changeListing(target, Instant.now(clock)));
        publish(RetailEvents.ITEM_LISTING_CHANGED, updated);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public RetailItem get(UUID id) {
        permissions.require(RetailPermissions.READ);
        return find(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RetailItem> list(String query, UUID supplierId, ListingStatus listing, int page, int size) {
        permissions.require(RetailPermissions.READ);
        return items.page(query, supplierId, listing, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    private String verifiedAtin(String atin, UUID itemId) {
        if (atin == null || !atin.matches("AT[0-9A-Z]{8}")) {
            throw new RetailRuleViolation("retail.atin-invalid", "ATIN must look like AT + 8 letters or digits");
        }
        CatalogVariant variant = catalog.liveVariant(atin).orElseThrow(() -> new RetailRuleViolation(
                "retail.atin-unknown", "Catalog has no live variant " + atin));
        if (!PLATFORM_OWNER.equals(variant.ownerKind())) {
            throw new RetailRuleViolation("retail.atin-not-platform", "ATIN " + atin
                    + " belongs to a marketplace seller, not AfroTransact Retail");
        }
        items.findByAtin(atin).filter(other -> !other.id().equals(itemId)).ifPresent(other -> {
            throw new ConflictException("retail.atin-linked", "ATIN " + atin + " is already linked to " + other.sku());
        });
        return atin;
    }

    private void checkSuppliers(ItemCommand c) {
        if (c.supplierItems() == null) {
            return;
        }
        for (SupplierItem si : c.supplierItems()) {
            supplier(si.supplierId());
        }
    }

    private Supplier supplier(UUID id) {
        if (id == null) {
            throw new RetailRuleViolation("retail.item-invalid", "Primary supplier is required");
        }
        return suppliers.findById(id).orElseThrow(() -> new RetailRuleViolation("retail.supplier-not-found",
                "Supplier " + id + " not found"));
    }

    private RetailItem find(UUID id) {
        return items.findById(id).orElseThrow(() -> new NotFoundException("retail.item-not-found", "Item not found"));
    }

    private static void checkVersion(RetailItem i, Long expected) {
        if (expected != null && expected != i.version()) {
            throw new RetailStateConflict("retail.stale-version", "Item " + i.sku() + " is at version " + i.version()
                    + ", not " + expected + "; reload and retry");
        }
    }

    private void publish(String type, RetailItem i) {
        events.publish(type, RetailEvents.AGGREGATE, i.id().toString(), RetailEventPayloads.ItemEvent.of(i));
    }
}
