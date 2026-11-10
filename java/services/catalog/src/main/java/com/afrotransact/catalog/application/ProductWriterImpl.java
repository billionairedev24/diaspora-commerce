package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.DepartmentScope;
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
import com.afrotransact.catalog.domain.VariantOptions;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SizeChartPolicy;
import com.afrotransact.catalog.domain.tax.SellerSkus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductDraftRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
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
    private final SizeChartPolicy sizePolicy;
    private final SizeChartRepository charts;
    private final ProductEvents events;
    private final BrandResolver brands;
    private final SellerScopeGuard scopeGuard;
    private final TaxGateEvaluator taxGate;
    private final Clock clock;

    ProductWriterImpl(ProductRepository products, CategoryRepository categories, CategoryRequestRepository requests,
                      ProductDraftRepository drafts, IdentifierIssuer ids, ProductDraftPolicy policy,
                      SizeChartPolicy sizePolicy, SizeChartRepository charts, ProductEvents events, BrandResolver brands, SellerScopeGuard scopeGuard,
                      TaxGateEvaluator taxGate, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.requests = requests;
        this.drafts = drafts;
        this.ids = ids;
        this.policy = policy;
        this.sizePolicy = sizePolicy;
        this.charts = charts;
        this.events = events;
        this.brands = brands;
        this.scopeGuard = scopeGuard;
        this.taxGate = taxGate;
        this.clock = clock;
    }

    @Override
    public Product create(ListingOwner owner, CreateProductCommand cmd) {
        Placement placement = placement(cmd.categoryId(), cmd.categoryRequestId(), owner);
        CategoryNode categoryNode = node(placement.categoryId());
        Map<String, String> attributes = brands.resolve(owner, categoryNode, placement.type(),
                normalised(placement.type(), cmd.attributes()), Map.of());
        List<VariantDraft> submitted = VariantOptions.canonical(placement.type(), cmd.variationTheme(),
                spelledLikeChart(placement.type(), cmd.details(), owner.orgId(), cmd.variants()));
        TaxGateEvaluator.Evaluation tax = taxGate.evaluate(placement.type(), categoryNode, factsOf(submitted),
                Collections.nCopies(submitted.size(), null), cmd.details().sellerBehaviour());
        ListingDetails details = cmd.details().withSellerBehaviour(tax.behaviour());
        ProductDraft draft = new ProductDraft(placement.type(), cmd.title(), attributes, cmd.images(),
                cmd.variationTheme(), submitted, details);
        validate(draft, owner.orgId(), null, tax.errors());
        for (VariantDraft v : submitted) {
            if (!sku(v).isEmpty() && products.sellerSkuTaken(owner.orgId(), sku(v))) {
                throw new ConflictException("catalog.sku-taken",
                        "SKU " + v.sellerSku() + " is already used by another of your products");
            }
            ensureGtinFree(v, null);
        }

        Instant now = Instant.now(clock);
        String currency = cmd.currency() == null ? "USD" : cmd.currency().toUpperCase(Locale.ROOT);
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < submitted.size(); i++) {
            variants.add(newVariant(submitted.get(i), currency, owner.orgId(), tax.variants().get(i)));
        }
        ProductStatus status = placement.request() == null
                ? ProductStatus.IN_REVIEW
                : ProductStatus.PENDING_CATEGORY_APPROVAL;
        boolean preorderEligible = cmd.preorderEligible() != null ? cmd.preorderEligible()
                : placement.type().traits().preorder() || Product.preorderDefault(placement.type().code());
        Product product = products.insert(new Product(UUID.randomUUID(), ids.nextAtin(), owner,
                placement.categoryId(), placement.request() == null ? null : placement.request().id(),
                placement.type().code(), cmd.title().trim(), attributes, cmd.images(), draft.variationTheme(),
                variants, status, null, now, now, 0, preorderEligible, Boolean.TRUE.equals(cmd.shipsNow()),
                details));
        dropDraft(cmd.draftId(), owner.orgId());
        events.publish(CatalogEvents.PRODUCT_CREATED, product);
        events.publishTaxFactsChanged(product, categoryNode, null, List.of(), Set.of());
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
            CategoryNode onNode = node(current.categoryId());
            boolean onRetiredNode = onNode != null && !onNode.active();
            // A listing on a node of the replaced initial tree may move to a current category, live or not: it cannot
            // be published again under the old node (it has no tax profile), so the seller must re-categorise it.
            if (current.published() && !onRetiredNode
                    || current.categoryRequestId() != null && current.categoryId() == null) {
                throw new CatalogRuleViolation("The category of a published listing cannot change",
                        Map.of("category", "Cannot change after publishing"));
            }
            Placement placement = placement(cmd.categoryId(), null, current.owner());
            categoryId = placement.categoryId();
            typeCode = placement.type().code();
        }
        ProductType type = type(typeCode);
        boolean moved = !Objects.equals(categoryId, current.categoryId());
        CategoryNode categoryNode = node(categoryId);
        Map<String, String> attributes = brands.resolve(current.owner(), categoryNode, type,
                normalised(type, cmd.attributes()), moved ? Map.of() : current.attributes());
        List<VariantDraft> submitted = VariantOptions.canonical(type, cmd.variationTheme(),
                spelledLikeChart(type, cmd.details(), current.owner().orgId(), cmd.variants()));
        List<Variant> matched = matchExisting(current.variants(), submitted);
        List<VariantTax> previous = new ArrayList<>();
        matched.forEach(m -> previous.add(m == null || moved ? null : m.tax()));
        TaxGateEvaluator.Evaluation tax = taxGate.evaluate(type, categoryNode, factsOf(submitted), previous,
                cmd.details().sellerBehaviour());
        ListingDetails details = cmd.details().withSellerBehaviour(tax.behaviour());
        ProductDraft draft = new ProductDraft(type, cmd.title(), attributes, cmd.images(), cmd.variationTheme(),
                submitted, details);
        validate(draft, current.owner().orgId(), current.details().sizeChartId(), tax.errors());

        List<Variant> next = new ArrayList<>();
        List<String> keptIds = new ArrayList<>();
        boolean termsChanged = !sameTerms(current.details(), details);
        Set<UUID> used = new HashSet<>();
        String currency = current.variants().isEmpty() ? "USD" : current.variants().getFirst().currency();
        for (int i = 0; i < submitted.size(); i++) {
            VariantDraft v = submitted.get(i);
            Variant old = matched.get(i);
            if (old == null) {
                if (!sku(v).isEmpty() && products.sellerSkuTaken(current.owner().orgId(), sku(v))) {
                    throw new ConflictException("catalog.sku-taken",
                            "SKU " + v.sellerSku() + " is already used by another of your products");
                }
                ensureGtinFree(v, current.id());
                next.add(newVariant(v, currency, current.owner().orgId(), tax.variants().get(i)));
                termsChanged = true;
                continue;
            }
            used.add(old.id());
            String sku = sku(v).isEmpty() ? old.sellerSku() : sku(v);
            String gtin = v.gtinExempt() ? null : blankToNull(Gtin.digits(v.gtin()));
            if (!Objects.equals(gtin, old.gtin())) {
                ensureGtinFree(v, current.id());
            }
            Variant updated = new Variant(old.id(), old.atin(), sku, gtin, v.gtinExempt(), v.options(), v.priceMinor(),
                    old.currency(), v.quantity(), v.compareAtMinor(), v.images(),
                    old.sellerSkuGenerated() && sku(v).isEmpty(), tax.variants().get(i));
            termsChanged |= updated.priceMinor() != old.priceMinor() || updated.quantity() != old.quantity()
                    || !Objects.equals(updated.compareAtMinor(), old.compareAtMinor());
            next.add(updated);
        }
        List<Variant> dropped = current.variants().stream().filter(v -> !used.contains(v.id())).toList();
        if (!dropped.isEmpty() && current.published()) {
            throw new CatalogRuleViolation("Variants of a published listing cannot be removed",
                    Map.of("variants", "Keep " + dropped.stream().map(Variant::sellerSku).collect(Collectors.joining(", "))
                            + " and set the quantity to 0 instead"));
        }
        boolean preorder = cmd.preorderEligible() != null ? cmd.preorderEligible() : current.preorderEligible();
        boolean shipsNow = cmd.shipsNow() != null ? cmd.shipsNow() : current.shipsNow();
        ProductStatus before = current.status();
        Product edited = current.edited(categoryId, typeCode, cmd.title().trim(), attributes, cmd.images(),
                draft.variationTheme(), next, details, preorder, shipsNow, Instant.now(clock));
        Product saved = products.updateWithVariants(edited);
        dropDraft(cmd.draftId(), current.owner().orgId());
        events.publish(CatalogEvents.PRODUCT_UPDATED, saved);
        if (termsChanged) {
            events.publish(CatalogEvents.PRODUCT_TERMS_CHANGED, saved);
        }
        if (before == ProductStatus.REJECTED && saved.status() == ProductStatus.IN_REVIEW) {
            events.publish(CatalogEvents.PRODUCT_RESUBMITTED, saved);
        }
        Set<String> changed = changedFacts(current, saved, categoryId);
        if (!changed.isEmpty() || moved) {
            CategoryNode previousNode = moved ? node(current.categoryId()) : null;
            events.publishTaxFactsChanged(saved, categoryNode, previousNode, current.variants(), changed);
        }
        return saved;
    }

    /**
     * The existing variant each submitted row stands for: by seller SKU, else GTIN, else ATIN (null for a new
     * variant). A variant is matched once.
     */
    private static List<Variant> matchExisting(List<Variant> existing, List<VariantDraft> submitted) {
        Map<String, Variant> bySku = new HashMap<>();
        Map<String, Variant> byGtin = new HashMap<>();
        Map<String, Variant> byAtin = new HashMap<>();
        for (Variant v : existing) {
            bySku.put(v.sellerSku(), v);
            if (v.gtin() != null) {
                byGtin.put(v.gtin(), v);
            }
            byAtin.put(v.atin().value(), v);
        }
        Set<UUID> taken = new HashSet<>();
        List<Variant> out = new ArrayList<>();
        for (VariantDraft d : submitted) {
            Variant hit = null;
            String sku = sku(d);
            String gtin = d.gtinExempt() ? "" : Gtin.digits(d.gtin());
            String atin = d.atin() == null ? "" : d.atin().trim().toUpperCase(Locale.ROOT);
            for (Variant candidate : new Variant[] {sku.isEmpty() ? null : bySku.get(sku),
                    gtin.isEmpty() ? null : byGtin.get(gtin), atin.isEmpty() ? null : byAtin.get(atin)}) {
                if (candidate != null && !taken.contains(candidate.id())) {
                    hit = candidate;
                    break;
                }
            }
            if (hit != null) {
                taken.add(hit.id());
            }
            out.add(hit);
        }
        return out;
    }

    private static List<Map<String, Object>> factsOf(List<VariantDraft> drafts) {
        return drafts.stream().map(VariantDraft::taxFacts).toList();
    }

    /** The fact ids whose answers differ between two states of a product (variants matched by ATIN). */
    private static Set<String> changedFacts(Product before, Product after, UUID categoryId) {
        Set<String> changed = new HashSet<>();
        Map<String, Variant> old = new HashMap<>();
        before.variants().forEach(v -> old.put(v.atin().value(), v));
        for (Variant v : after.variants()) {
            Variant o = old.get(v.atin().value());
            Map<String, Object> was = o == null ? Map.of() : o.tax().facts();
            Set<String> keys = new HashSet<>(v.tax().facts().keySet());
            keys.addAll(was.keySet());
            for (String k : keys) {
                if (!Objects.equals(String.valueOf(v.tax().facts().get(k)), String.valueOf(was.get(k)))) {
                    changed.add(k);
                }
            }
        }
        Map<String, Object> nowBehaviour = after.details().sellerBehaviour();
        Map<String, Object> wasBehaviour = before.details().sellerBehaviour();
        Set<String> keys = new HashSet<>(nowBehaviour.keySet());
        keys.addAll(wasBehaviour.keySet());
        for (String k : keys) {
            if (!Objects.equals(String.valueOf(nowBehaviour.get(k)), String.valueOf(wasBehaviour.get(k)))) {
                changed.add(k);
            }
        }
        return changed;
    }

    /** The sizes of the variants spelled as the seller's size chart spells them (a chart of another owner is ignored). */
    private List<VariantDraft> spelledLikeChart(ProductType type, ListingDetails details, String ownerOrg,
                                                List<VariantDraft> variants) {
        if (details.sizeChartId() == null || type.sizing().isEmpty()) {
            return variants;
        }
        SizeChart chart = charts.findById(details.sizeChartId()).filter(c -> c.ownerOrg().equals(ownerOrg)).orElse(null);
        return VariantOptions.spelledLikeChart(type.sizing().get().sizeDimension(), chart, variants);
    }

    /**
     * The product rules and the size chart rules together, so the seller sees every problem at once.
     *
     * @param linkedChartId the size chart the stored product already links (it may stay linked once retired)
     */
    private void validate(ProductDraft draft, String ownerOrg, UUID linkedChartId, Map<String, String> taxErrors) {
        Map<String, String> errors = new LinkedHashMap<>();
        try {
            policy.validate(draft);
        } catch (CatalogRuleViolation ex) {
            errors.putAll(ex.fieldErrors());
        }
        UUID chartId = draft.details().sizeChartId();
        SizeChart chart = chartId == null ? null : charts.findById(chartId).orElse(null);
        sizePolicy.problems(new SizeChartPolicy.SizedListing(draft.productType(), ownerOrg, draft.variationTheme(),
                draft.variants(), chartId, chart, linkedChartId)).forEach(errors::putIfAbsent);
        taxErrors.forEach(errors::putIfAbsent);
        if (!errors.isEmpty()) {
            throw new CatalogRuleViolation("The product has " + errors.size() + " problem(s)", errors);
        }
    }

    /** A new variant; an empty seller SKU is generated (stable, organisation-prefixed, unique for the organisation). */
    private Variant newVariant(VariantDraft v, String currency, String orgId, VariantTax tax) {
        String gtin = v.gtinExempt() ? null : blankToNull(Gtin.digits(v.gtin()));
        Atin atin = ids.nextAtin();
        String sku = sku(v);
        boolean generated = sku.isEmpty();
        if (generated) {
            sku = SellerSkus.generate(orgId, atin.value());
            for (int attempt = 2; products.sellerSkuTaken(orgId, sku); attempt++) {
                sku = SellerSkus.withSuffix(SellerSkus.generate(orgId, atin.value()), attempt);
            }
        }
        return new Variant(UUID.randomUUID(), atin, sku, gtin, v.gtinExempt(), v.options(), v.priceMinor(), currency,
                v.quantity(), v.compareAtMinor(), v.images(), generated, tax);
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
        DepartmentScope scope = scopeGuard.scopeOf(owner);
        if (categoryId != null) {
            CategoryNode node = categories.findById(categoryId)
                    .filter(CategoryNode::active)
                    .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Category not found"));
            scope.require(node);
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
        scope.requirePath(request.parentPath());
        return new Placement(null, request, type(ProductType.CUSTOM));
    }

    private CategoryNode node(UUID categoryId) {
        return categoryId == null ? null : categories.findById(categoryId).orElse(null);
    }

    private ProductType type(String code) {
        return categories.findProductType(code)
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type"));
    }

    static String sku(VariantDraft v) {
        return SellerSkus.normalise(v.sellerSku());
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private record Placement(UUID categoryId, CategoryRequest request, ProductType type) {
    }
}
