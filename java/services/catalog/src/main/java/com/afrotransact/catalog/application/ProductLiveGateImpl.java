package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.ImageHeld;
import com.afrotransact.catalog.domain.tax.LiveRequiresTax;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
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
    private final TaxAuthorityCheck authority;
    private final ImageGate imageGate;
    private final Clock clock;

    ProductLiveGateImpl(ProductRepository products, ProductTaxHoldRepository holds, CategoryRepository categories,
                        ProductEvents events, SellingPublishGate sellingGate, TaxGateMetrics metrics,
                        TaxAuthorityCheck authority, ImageGate imageGate, Clock clock) {
        this.products = products;
        this.holds = holds;
        this.categories = categories;
        this.events = events;
        this.sellingGate = sellingGate;
        this.metrics = metrics;
        this.authority = authority;
        this.imageGate = imageGate;
        this.clock = clock;
    }

    @Override
    public Product approve(Product product, Instant now) {
        requireReady(product);
        requireAuthority(product);
        Product cleared = requirePhotosCleared(product, now);
        Product live = cleared.approve(now);
        holds.release(product.id(), now);
        return live;
    }

    @Override
    public Product resume(Product product, Instant now) {
        requireReady(product);
        requireAuthority(product);
        return requirePhotosCleared(product, now).resume(now);
    }

    @Override
    public Product restoreFromTakedown(Product product, ProductStatus previous, Instant now) {
        Product restored = closeIfNotReady(product.restoreFromTakedown(previous, now), now);
        if (restored.status() != ProductStatus.LIVE) {
            return restored;
        }
        TaxAuthorityCheck.Verdict verdict = verdictOrNull(restored);
        if (verdict == null && authority.consulted()) {
            // tax-returns could not be asked: never put it on sale on a guess. It returns held and the sweep reopens it.
            return hold(restored, "TAX_RETURNS_UNREACHABLE", now, false);
        }
        return verdict != null && !verdict.resolved() ? hold(restored, verdict.reasonCode(), now, true) : restored;
    }

    @Override
    public Product closeIfNotReady(Product product, Instant now) {
        if (LiveRequiresTax.holds(product)) {
            return product;
        }
        return hold(product, LiveRequiresTax.reasonCode(product), now, false);
    }

    @Override
    public Product closeForTaxReturns(UUID productId, String reasonCode) {
        Product p = products.findById(productId).orElse(null);
        if (p == null || p.status() != ProductStatus.LIVE) {
            return p;
        }
        return products.update(hold(p, reasonCode, Instant.now(clock), true));
    }

    /** Takes a live listing off sale with the plain note, the hold and the announcement (search, offers and carts drop it). */
    private Product hold(Product product, String reason, Instant now, boolean disagreement) {
        boolean photo = "IMAGE_HELD".equals(reason);
        Product held = photo ? product.holdForImage(now) : product.holdForTax(now);
        holds.hold(product.id(), reason, now);
        events.publishWithReason(CatalogEvents.PRODUCT_PAUSED, held, reason);
        holds.markAnnounced(product.id(), now);
        metrics.closed(reason);
        if (disagreement) {
            metrics.disagreement(reason);
        }
        LOG.warn("Listing {} closed ({}): {}{}", product.atin().value(), reason,
                photo ? "a photo is held for review" : "a variant has no resolved tax decision",
                disagreement ? " in tax-returns, the authority checkout reads" : "");
        return held;
    }

    @Override
    public Product reconcile(UUID productId) {
        return reconcileChecked(productId).product();
    }

    @Override
    public Reconciliation reconcileChecked(UUID productId) {
        Product p = products.findById(productId).orElse(null);
        if (p == null) {
            return new Reconciliation(null, false);
        }
        Instant now = Instant.now(clock);
        // 1. Tax first. Nothing in this block asks media: a media outage must never keep a LIVE listing without a
        //    resolved tax decision on sale.
        if (p.status() == ProductStatus.LIVE && !LiveRequiresTax.holds(p)) {
            return new Reconciliation(products.update(closeIfNotReady(p, now)), false);
        }
        if (p.status() == ProductStatus.LIVE) {
            TaxAuthorityCheck.Verdict verdict = verdictOrNull(p);
            if (verdict != null && !verdict.resolved()) {
                return new Reconciliation(products.update(hold(p, verdict.reasonCode(), now, true)), false);
            }
        }
        // 2. Only now the held photos. Media unavailable: skip this part, keep the state (fail closed).
        PhotoLookup photos = clearedPhotosOrSame(p, now);
        p = photos.product();
        if (p.status() == ProductStatus.IN_REVIEW && TaxReadiness.of(p, null, null).ready() && !p.hasHeldImage()
                && holds.isHeld(p.id()) && authorityAllows(p) && mayStillSell(p)) {
            Product live = products.update(p.restoreAfterTaxHold(now));
            holds.release(p.id(), now);
            events.publish(CatalogEvents.PRODUCT_RESUMED, live);
            metrics.restored();
            LOG.info("Listing {} back on sale: every variant has a resolved tax decision", p.atin().value());
            return new Reconciliation(live, photos.skipped());
        }
        if (p.status() != ProductStatus.IN_REVIEW) {
            // The listing is no longer waiting on tax (a person decided, or it was never closed): forget the hold.
            holds.release(p.id(), now);
        }
        return new Reconciliation(p, photos.skipped());
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

    /** tax-returns' answer for the product; null when it cannot be asked or is not consulted (the caller keeps its state). */
    private TaxAuthorityCheck.Verdict verdictOrNull(Product p) {
        if (!authority.consulted()) {
            return null;
        }
        try {
            return authority.verdicts(List.of(p)).get(p.id());
        } catch (TaxDecisionAuthority.Unavailable ex) {
            return null;
        }
    }

    /** True when tax-returns answers RESOLVED for every variant (or the authority is switched off in tests). */
    private boolean authorityAllows(Product p) {
        if (!authority.consulted()) {
            return true;
        }
        TaxAuthorityCheck.Verdict v = verdictOrNull(p);
        return v != null && v.resolved();
    }

    /** Going live needs tax-returns' RESOLVED on every variant; when it cannot be asked the answer is no, in plain words. */
    private void requireAuthority(Product product) {
        if (!authority.consulted()) {
            return;
        }
        TaxReadiness local = TaxReadiness.of(product, null, null);
        TaxAuthorityCheck.Verdict v;
        try {
            v = authority.verdicts(List.of(product)).get(product.id());
        } catch (TaxDecisionAuthority.Unavailable ex) {
            throw new TaxNotReady("We can't confirm this product's tax details right now. Try again in a moment.",
                    Map.of("tax", "Tax review could not be reached."), local);
        }
        if (!v.resolved()) {
            throw new TaxNotReady("This product cannot go live yet: its tax details are not approved by tax review",
                    v.blockers(), local);
        }
    }

    /**
     * A listing with a photo held for review cannot go live (S390): media is asked again, a cleared photo is published into
     * the listing, and a photo still held (or refused) keeps it off sale with a plain word to the seller. A product with no
     * held photo is returned as it is and media is not asked.
     */
    private Product requirePhotosCleared(Product product, Instant now) {
        if (!product.hasHeldImage()) {
            return product;
        }
        ImageGate.Resolved resolved = imageGate.resolveHeld(product.images(), variantPictures(product),
                product.owner().orgId());
        if (resolved.stillHeld() || resolved.rejected()) {
            throw new ImageHeld(resolved.rejected()
                    ? "This product cannot go live: one of its photos was not accepted"
                    : "This product cannot go live yet: we are checking your photo");
        }
        return product.withImages(resolved.images(), withPictures(product, resolved), now);
    }

    private record PhotoLookup(Product product, boolean skipped) {
    }

    /**
     * Publishes the held photos media has cleared since the last look. When media cannot be asked the product is returned
     * unchanged and the skip is counted and logged: a held photo stays held, nothing is published on an unknown answer.
     */
    private PhotoLookup clearedPhotosOrSame(Product p, Instant now) {
        if (!p.hasHeldImage()) {
            return new PhotoLookup(p, false);
        }
        ImageGate.Resolved resolved;
        try {
            resolved = imageGate.resolveHeld(p.images(), variantPictures(p), p.owner().orgId());
        } catch (ImageServiceUnavailable ex) {
            metrics.mediaUnavailable();
            LOG.warn("Listing {} ({}): media is unavailable, held photo check skipped and the listing stays as it is: {}",
                    p.atin().value(), p.id(), ex.getMessage());
            return new PhotoLookup(p, true);
        } catch (RuntimeException ex) {
            LOG.warn("Listing {} ({}): held photos could not be looked up now, check skipped: {}", p.atin().value(),
                    p.id(), ex.getMessage());
            return new PhotoLookup(p, true);
        }
        List<Variant> variants = withPictures(p, resolved);
        return new PhotoLookup(resolved.images().equals(p.images()) && variants.equals(p.variants()) ? p
                : products.update(p.withImages(resolved.images(), variants, now)), false);
    }

    private static List<List<ProductImage>> variantPictures(Product product) {
        return product.variants().stream().map(Variant::images).toList();
    }

    /** The variants with their own pictures as media settled them (cleared held photos published). */
    private static List<Variant> withPictures(Product product, ImageGate.Resolved resolved) {
        if (resolved.variantImages().size() != product.variants().size()) {
            return product.variants();
        }
        List<Variant> out = new ArrayList<>();
        for (int i = 0; i < product.variants().size(); i++) {
            Variant v = product.variants().get(i);
            out.add(v.images().equals(resolved.variantImages().get(i)) ? v : v.withImages(resolved.variantImages().get(i)));
        }
        return out;
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
