package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.FlagSource;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductTakedown;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductFlagRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTakedownRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ListingModerationServiceImpl implements ListingModerationService {

    private final ProductRepository products;
    private final ProductFlagRepository flags;
    private final ProductTakedownRepository takedowns;
    private final ListingSuppressionRepository suppressions;
    private final PermissionChecker permissions;
    private final ActorResolver actors;
    private final ProductEvents events;
    private final Clock clock;

    ListingModerationServiceImpl(ProductRepository products, ProductFlagRepository flags,
                                 ProductTakedownRepository takedowns, ListingSuppressionRepository suppressions,
                                 PermissionChecker permissions, ActorResolver actors, ProductEvents events,
                                 Clock clock) {
        this.products = products;
        this.flags = flags;
        this.takedowns = takedowns;
        this.suppressions = suppressions;
        this.permissions = permissions;
        this.actors = actors;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReportResult report(String atin, ListingReasonCode reasonCode, String details) {
        Actor actor = actors.current();
        Product product = publicProduct(atin);
        ProductFlag flag = ProductFlag.raise(UUID.randomUUID(), product, FlagSource.CUSTOMER_REPORT, reasonCode,
                details, actor.subject(), null, Instant.now(clock));
        ProductFlagRepository.Stored stored = flags.insert(flag);
        if (stored.created()) {
            events.publishFlag(product, stored.flag());
        }
        return new ReportResult(stored.flag(), stored.created());
    }

    @Override
    @Transactional
    public ProductFlag flag(UUID productId, ListingReasonCode reasonCode, String details, String riskLevel) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        Product product = find(productId);
        FlagSource source = actor.type() == PrincipalType.SERVICE || actor.type() == PrincipalType.AI_AGENT
                ? FlagSource.AUTOMATED : FlagSource.STAFF;
        ProductFlag stored = flags.insert(ProductFlag.raise(UUID.randomUUID(), product, source, reasonCode, details,
                actor.subject(), riskLevel, Instant.now(clock))).flag();
        events.publishFlag(product, stored);
        return stored;
    }

    @Override
    @Transactional
    public Product takeDown(UUID productId, ListingReasonCode reasonCode, String note) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        Product product = find(productId);
        if (product.status() == ProductStatus.TAKEN_DOWN) {
            return product;
        }
        Instant now = Instant.now(clock);
        ProductTakedown takedown = ProductTakedown.open(UUID.randomUUID(), product, reasonCode, note,
                actor.subject(), now);
        // The optimistic product update goes first, so a concurrent takedown fails with 409 before inserting.
        Product updated = products.update(product.takeDown(takedown.productNote(), now));
        takedowns.insert(takedown);
        events.publishWithReason(CatalogEvents.PRODUCT_TAKEN_DOWN, updated, takedown.reasonCode().name());
        return updated;
    }

    @Override
    @Transactional
    public Product restore(UUID productId, String note) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        Product product = find(productId);
        if (product.status() != ProductStatus.TAKEN_DOWN) {
            return product;
        }
        Instant now = Instant.now(clock);
        Optional<ProductTakedown> active = takedowns.findActive(productId);
        ProductStatus previous = active.map(ProductTakedown::previousStatus).orElse(ProductStatus.LIVE);
        Product updated = products.update(product.restoreFromTakedown(previous, now));
        active.ifPresent(t -> takedowns.update(t.restore(actor.subject(), note, now)));
        events.publish(CatalogEvents.PRODUCT_REINSTATED, updated);
        return updated;
    }

    /** LIVE and not hidden by seller-service enforcement; anything else is "not found" for shoppers. */
    private Product publicProduct(String raw) {
        Atin atin;
        try {
            atin = new Atin(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw notFound(raw);
        }
        return products.findByAtin(atin)
                .filter(p -> p.status() == ProductStatus.LIVE && !suppressions.isHidden(p.owner().orgId()))
                .orElseThrow(() -> notFound(raw));
    }

    private Product find(UUID id) {
        return products.findById(id).orElseThrow(() -> notFound(id.toString()));
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("catalog.product-not-found", "Product " + id + " not found");
    }
}
