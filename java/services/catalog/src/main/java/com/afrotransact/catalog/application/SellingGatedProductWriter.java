package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Wraps the product writer with the selling approvals of docs/engineering/listing-rules.md: a listing is checked once it
 * has passed the writer's validation, and refused (the transaction rolls back, so the form's draft stays) unless the seller
 * may sell it. A listing in a scope the seller may sell goes live straight away when its tax details are ready: no human
 * review, only flagged listings are reviewed (the trust-ops flow is unchanged). Used for the form, the bulk upload and the
 * image library, which all write through {@link ProductWriter}.
 */
@Component
@Primary
class SellingGatedProductWriter implements ProductWriter {

    private final ProductWriter delegate;
    private final SellingPublishGate gate;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductEvents events;
    private final ProductLiveGate liveGate;
    private final Clock clock;
    private final boolean autoPublish;

    SellingGatedProductWriter(@Qualifier("productWriterImpl") ProductWriter delegate, SellingPublishGate gate,
                              ProductRepository products, CategoryRepository categories, ProductEvents events,
                              ProductLiveGate liveGate, Clock clock,
                              @Value("${at.catalog.listing.auto-publish:true}") boolean autoPublish) {
        this.delegate = delegate;
        this.gate = gate;
        this.products = products;
        this.categories = categories;
        this.events = events;
        this.liveGate = liveGate;
        this.clock = clock;
        this.autoPublish = autoPublish;
    }

    @Override
    public Product create(ListingOwner owner, CreateProductCommand command) {
        Product created = delegate.create(owner, command);
        if (!gate.enabled()) {
            return created;
        }
        gate.requireApprovable(created);
        return publishIfClear(created);
    }

    @Override
    public Product update(Product current, UpdateProductCommand command) {
        Product saved = delegate.update(current, command);
        if (!gate.enabled()) {
            return saved;
        }
        // Only a change of what the listing is (category, brand, material) needs the seller's approval for it.
        SellingPublishGate.Facts before = gate.factsOf(current);
        SellingPublishGate.Facts after = gate.factsOf(saved);
        boolean moved = !Objects.equals(before.categoryId(), after.categoryId())
                || !Objects.equals(before.attributes().get("brand"), after.attributes().get("brand"))
                || !Objects.equals(before.attributes().get("material"), after.attributes().get("material"));
        if (moved || current.status() == ProductStatus.REJECTED) {
            gate.requireApprovable(saved);
        }
        return current.status() == ProductStatus.REJECTED ? publishIfClear(saved) : saved;
    }

    /** A listing waiting only for review goes live when its tax details are ready; otherwise it waits as before. */
    private Product publishIfClear(Product p) {
        if (!autoPublish || p.status() != ProductStatus.IN_REVIEW || p.categoryId() == null) {
            return p;
        }
        CategoryNode node = categories.findById(p.categoryId()).orElse(null);
        TaxReadiness readiness = TaxReadiness.of(p, node == null ? null : node.taxProfileId(),
                node == null ? null : node.taxStatus());
        if (!readiness.ready()) {
            return p;
        }
        // Through the live gate (which re-checks the tax decision of every variant, tax-returns' included), never
        // Product.approve directly. A listing tax-returns has not approved yet (or cannot be asked) waits for review.
        Product live;
        try {
            live = products.update(liveGate.approve(p, Instant.now(clock)));
        } catch (TaxNotReady ex) {
            return p;
        }
        events.publish(CatalogEvents.PRODUCT_APPROVED, live);
        return live;
    }
}
