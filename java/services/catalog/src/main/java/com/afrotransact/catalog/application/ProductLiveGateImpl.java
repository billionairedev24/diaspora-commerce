package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.tax.LiveRequiresTax;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.TaxGateMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ProductLiveGateImpl implements ProductLiveGate {

    private static final Logger LOG = LoggerFactory.getLogger(ProductLiveGateImpl.class);

    private final ProductRepository products;
    private final ProductTaxHoldRepository holds;
    private final CategoryRepository categories;
    private final ProductEvents events;
    private final SellingPublishGate sellingGate;
    private final TaxGateMetrics metrics;
    private final Clock clock;

    ProductLiveGateImpl(ProductRepository products, ProductTaxHoldRepository holds, CategoryRepository categories,
                        ProductEvents events, SellingPublishGate sellingGate, TaxGateMetrics metrics, Clock clock) {
        this.products = products;
        this.holds = holds;
        this.categories = categories;
        this.events = events;
        this.sellingGate = sellingGate;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Product approve(Product product, Instant now) {
        requireReady(product);
        Product live = product.approve(now);
        holds.release(product.id(), now);
        return live;
    }

    @Override
    public Product resume(Product product, Instant now) {
        requireReady(product);
        return product.resume(now);
    }

    @Override
    public Product restoreFromTakedown(Product product, ProductStatus previous, Instant now) {
        Product restored = product.restoreFromTakedown(previous, now);
        return closeIfNotReady(restored, now);
    }

    @Override
    public Product closeIfNotReady(Product product, Instant now) {
        if (LiveRequiresTax.holds(product)) {
            return product;
        }
        String reason = LiveRequiresTax.reasonCode(product);
        Product held = product.holdForTax(now);
        holds.hold(product.id(), reason, now);
        events.publishWithReason(CatalogEvents.PRODUCT_PAUSED, held, reason);
        holds.markAnnounced(product.id(), now);
        metrics.closed(reason);
        LOG.warn("Listing {} closed for tax ({}): a variant has no resolved tax decision", product.atin().value(), reason);
        return held;
    }

    @Override
    public Product reconcile(UUID productId) {
        Product p = products.findById(productId).orElse(null);
        if (p == null) {
            return null;
        }
        Instant now = Instant.now(clock);
        if (p.status() == ProductStatus.LIVE && !LiveRequiresTax.holds(p)) {
            return products.update(closeIfNotReady(p, now));
        }
        if (p.status() == ProductStatus.IN_REVIEW && TaxReadiness.of(p, null, null).ready() && holds.isHeld(p.id())
                && mayStillSell(p)) {
            Product live = products.update(p.restoreAfterTaxHold(now));
            holds.release(p.id(), now);
            events.publish(CatalogEvents.PRODUCT_RESUMED, live);
            metrics.restored();
            LOG.info("Listing {} back on sale: every variant has a resolved tax decision", p.atin().value());
            return live;
        }
        if (p.status() != ProductStatus.IN_REVIEW) {
            // The listing is no longer waiting on tax (a person decided, or it was never closed): forget the hold.
            holds.release(p.id(), now);
        }
        return p;
    }

    @Override
    public void releaseHold(UUID productId, Instant now) {
        holds.release(productId, now);
    }

    /** The seller must still be allowed to sell it (selling approvals); anything uncertain keeps it held. */
    private boolean mayStillSell(Product p) {
        if (!sellingGate.enabled()) {
            return true;
        }
        try {
            return sellingGate.evaluate(p.owner(), sellingGate.factsOf(p)).sell();
        } catch (RuntimeException ex) {
            LOG.warn("Listing {} stays held: could not confirm the seller may sell it", p.atin().value(), ex);
            return false;
        }
    }

    private void requireReady(Product product) {
        CategoryNode node = product.categoryId() == null ? null : categories.findById(product.categoryId()).orElse(null);
        TaxReadiness readiness = TaxReadiness.of(product, node == null ? null : node.taxProfileId(),
                node == null ? null : node.taxStatus());
        if (!readiness.ready()) {
            throw new TaxNotReady("This product cannot go live yet: its tax details are not complete",
                    readiness.blockers(), readiness);
        }
    }
}
