package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.ListingDetails;
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
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

@Component
class ProductWriterImpl implements ProductWriter {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CategoryRequestRepository requests;
    private final ProductDraftRepository drafts;
    private final IdentifierIssuer ids;
    private final ProductDraftPolicy policy;
    private final ProductEvents events;
    private final Clock clock;

    ProductWriterImpl(ProductRepository products, CategoryRepository categories, CategoryRequestRepository requests,
                      ProductDraftRepository drafts, IdentifierIssuer ids, ProductDraftPolicy policy,
                      ProductEvents events, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.requests = requests;
        this.drafts = drafts;
        this.ids = ids;
        this.policy = policy;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Product create(ListingOwner owner, CreateProductCommand cmd) {
        Placement placement = placement(cmd.categoryId(), cmd.categoryRequestId(), owner);
        Map<String, String> attributes = normalised(placement.type(), cmd.attributes());
        ProductDraft draft = new ProductDraft(placement.type(), cmd.title(), attributes, cmd.images(),
                cmd.variationTheme(), cmd.variants(), cmd.details());
        policy.validate(draft);
        for (VariantDraft v : cmd.variants()) {
            if (products.sellerSkuTaken(owner.orgId(), sku(v))) {
                throw new ConflictException("catalog.sku-taken",
                        "SKU " + v.sellerSku() + " is already used by another of your products");
            }
            ensureGtinFree(v, null);
        }

        Instant now = Instant.now(clock);
        String currency = cmd.currency() == null ? "USD" : cmd.currency().toUpperCase(Locale.ROOT);
        List<Variant> variants = new ArrayList<>();
        for (VariantDraft v : cmd.variants()) {
            variants.add(newVariant(v, currency));
        }
        ProductStatus status = placement.request() == null
                ? ProductStatus.IN_REVIEW
                : ProductStatus.PENDING_CATEGORY_APPROVAL;
        boolean preorderEligible = cmd.preorderEligible() != null ? cmd.preorderEligible()
                : Product.preorderDefault(placement.type().code());
        Product product = products.insert(new Product(UUID.randomUUID(), ids.nextAtin(), owner,
                placement.categoryId(), placement.request() == null ? null : placement.request().id(),
                placement.type().code(), cmd.title().trim(), attributes, cmd.images(), draft.variationTheme(),
                variants, status, null, now, now, 0, preorderEligible, Boolean.TRUE.equals(cmd.shipsNow()),
                cmd.details()));
        dropDraft(cmd.draftId(), owner.orgId());
        events.publish(CatalogEvents.PRODUCT_CREATED, product);
        return product;
    }

    @Override
    public Product update(Product current, UpdateProductCommand cmd) {
        if (current.status() == ProductStatus.TAKEN_DOWN) {
            throw new ConflictException("catalog.listing-taken-down",
                    "This listing was taken down by Trust and Safety and cannot be edited");
        }
        if (cmd.version() != null && cmd.version() != current.version()) {
            throw new ConflictException("catalog.concurrent-update",
                    "This product was changed since you opened it; reload and try again");
        }
        UUID categoryId = current.categoryId();
        String typeCode = current.productTypeCode();
        if (cmd.categoryId() != null && !cmd.categoryId().equals(current.categoryId())) {
            if (current.published() || current.categoryRequestId() != null && current.categoryId() == null) {
                throw new CatalogRuleViolation("The category of a published listing cannot change",
                        Map.of("category", "Cannot change after publishing"));
            }
            Placement placement = placement(cmd.categoryId(), null, current.owner());
            categoryId = placement.categoryId();
            typeCode = placement.type().code();
        }
        ProductType type = type(typeCode);
        Map<String, String> attributes = normalised(type, cmd.attributes());
        ProductDraft draft = new ProductDraft(type, cmd.title(), attributes, cmd.images(), cmd.variationTheme(),
                cmd.variants(), cmd.details());
        policy.validate(draft);

        Map<String, Variant> bySku = new HashMap<>();
        current.variants().forEach(v -> bySku.put(v.sellerSku(), v));
        List<Variant> next = new ArrayList<>();
        boolean termsChanged = !sameTerms(current.details(), cmd.details());
        for (VariantDraft v : cmd.variants()) {
            String sku = sku(v);
            Variant old = bySku.remove(sku);
            if (old == null) {
                if (products.sellerSkuTaken(current.owner().orgId(), sku)) {
                    throw new ConflictException("catalog.sku-taken",
                            "SKU " + v.sellerSku() + " is already used by another of your products");
                }
                ensureGtinFree(v, current.id());
                next.add(newVariant(v, current.variants().isEmpty() ? "USD" : current.variants().getFirst().currency()));
                termsChanged = true;
                continue;
            }
            String gtin = v.gtinExempt() ? null : blankToNull(Gtin.digits(v.gtin()));
            if (!Objects.equals(gtin, old.gtin())) {
                ensureGtinFree(v, current.id());
            }
            Variant updated = new Variant(old.id(), old.atin(), sku, gtin, v.gtinExempt(), v.options(), v.priceMinor(),
                    old.currency(), v.quantity(), v.compareAtMinor(), v.images());
            termsChanged |= updated.priceMinor() != old.priceMinor() || updated.quantity() != old.quantity()
                    || !Objects.equals(updated.compareAtMinor(), old.compareAtMinor());
            next.add(updated);
        }
        if (!bySku.isEmpty() && current.published()) {
            throw new CatalogRuleViolation("Variants of a published listing cannot be removed",
                    Map.of("variants", "Keep " + String.join(", ", bySku.keySet())
                            + " and set the quantity to 0 instead"));
        }
        boolean preorder = cmd.preorderEligible() != null ? cmd.preorderEligible() : current.preorderEligible();
        boolean shipsNow = cmd.shipsNow() != null ? cmd.shipsNow() : current.shipsNow();
        ProductStatus before = current.status();
        Product edited = current.edited(categoryId, typeCode, cmd.title().trim(), attributes, cmd.images(),
                draft.variationTheme(), next, cmd.details(), preorder, shipsNow, Instant.now(clock));
        Product saved = products.updateWithVariants(edited);
        dropDraft(cmd.draftId(), current.owner().orgId());
        events.publish(CatalogEvents.PRODUCT_UPDATED, saved);
        if (termsChanged) {
            events.publish(CatalogEvents.PRODUCT_TERMS_CHANGED, saved);
        }
        if (before == ProductStatus.REJECTED && saved.status() == ProductStatus.IN_REVIEW) {
            events.publish(CatalogEvents.PRODUCT_RESUBMITTED, saved);
        }
        return saved;
    }

    private Variant newVariant(VariantDraft v, String currency) {
        String gtin = v.gtinExempt() ? null : blankToNull(Gtin.digits(v.gtin()));
        return new Variant(UUID.randomUUID(), ids.nextAtin(), sku(v), gtin, v.gtinExempt(), v.options(),
                v.priceMinor(), currency, v.quantity(), v.compareAtMinor(), v.images());
    }

    private static boolean sameTerms(ListingDetails a, ListingDetails b) {
        return a.condition() == b.condition() && a.fulfilment() == b.fulfilment()
                && a.handlingDays() == b.handlingDays() && Objects.equals(a.shipFrom(), b.shipFrom());
    }

    /** Attribute values in their stored form (numbers with their unit); blank values dropped. */
    private static Map<String, String> normalised(ProductType type, Map<String, String> attributes) {
        Map<String, String> out = new LinkedHashMap<>();
        attributes.forEach((k, v) -> {
            if (v == null || v.isBlank()) {
                return;
            }
            String value = type.attribute(k).map(a -> a.normalise(v)).orElse(v.trim());
            out.put(k, value);
        });
        return out;
    }

    private void ensureGtinFree(VariantDraft v, UUID ownProductId) {
        String gtin = Gtin.digits(v.gtin());
        if (v.gtinExempt() || gtin.isEmpty()) {
            return;
        }
        products.findByVariantGtin(gtin).filter(existing -> !existing.id().equals(ownProductId)).ifPresent(existing -> {
            throw new ConflictException("catalog.gtin-exists",
                    "GTIN " + gtin + " already has a product page (" + existing.atin()
                            + "). Add an offer to it instead of creating a duplicate.");
        });
    }

    private void dropDraft(UUID draftId, String orgId) {
        if (draftId == null) {
            return;
        }
        drafts.findById(draftId).filter(d -> d.ownerOrg().equals(orgId)).ifPresent(d -> drafts.delete(d.id()));
    }

    private Placement placement(UUID categoryId, UUID categoryRequestId, ListingOwner owner) {
        if ((categoryId == null) == (categoryRequestId == null)) {
            throw new CatalogRuleViolation("Choose a product type, or request a new category",
                    Map.of("category", "Required"));
        }
        if (categoryId != null) {
            CategoryNode node = categories.findById(categoryId)
                    .filter(CategoryNode::active)
                    .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Category not found"));
            if (node.level() != CategoryLevel.PRODUCT_TYPE) {
                throw new CatalogRuleViolation("Pick a product type (third level)", Map.of("category", "Too broad"));
            }
            return new Placement(node.id(), null, type(node.productTypeCode()));
        }
        CategoryRequest request = requests.findById(categoryRequestId)
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

    static String sku(VariantDraft v) {
        return v.sellerSku() == null ? "" : v.sellerSku().trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private record Placement(UUID categoryId, CategoryRequest request, ProductType type) {
    }
}
