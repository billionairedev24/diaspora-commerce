package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ChangeRequestPolicy;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.GtinAlreadyUsed;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.VariantOptions;
import com.afrotransact.catalog.domain.tax.SellerSkus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

@Component
class PageVariantServiceImpl implements PageVariantService {

    static final String INVALID = "catalog.change-request-variant-invalid";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final IdentifierIssuer ids;
    private final TaxGateEvaluator taxGate;
    private final ProductLiveGate liveGate;
    private final ImageGate imageGate;
    private final CatalogSettingsService settings;
    private final ProductEvents productEvents;
    private final DomainEventPublisher events;

    PageVariantServiceImpl(ProductRepository products, CategoryRepository categories, IdentifierIssuer ids,
                           TaxGateEvaluator taxGate, ProductLiveGate liveGate, ImageGate imageGate,
                           CatalogSettingsService settings, ProductEvents productEvents, DomainEventPublisher events) {
        this.products = products;
        this.categories = categories;
        this.ids = ids;
        this.taxGate = taxGate;
        this.liveGate = liveGate;
        this.imageGate = imageGate;
        this.settings = settings;
        this.productEvents = productEvents;
        this.events = events;
    }

    @Override
    public Added addVariant(ProductChangeRequest r, Product page, boolean holdPageForTax, Instant now) {
        if (page.variants().isEmpty() || page.variationTheme().isBlank()) {
            throw invalid("This page has no sizes or colours to add to. Reject the request, or ask the page owner to add a "
                    + "variation theme first.");
        }
        CategoryNode node = node(page);
        ProductType type = categories.findProductType(page.productTypeCode())
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type"));
        Variant template = page.variants().getFirst();
        Map<String, String> options = newOptions(r, page, type);
        ProductImage[] pictures = variantPictures(r, page);

        TaxGateEvaluator.Evaluation tax = taxGate.evaluate(type, node, List.of(template.tax().facts()),
                Collections.nCopies(1, null), page.details().sellerBehaviour());
        if (!tax.errors().isEmpty()) {
            throw new BusinessRuleException(ChangeRequestApplierImpl.NEEDS_TAX_REVIEW,
                    "The tax questions of this kind of product are not answered for the new variant: "
                            + String.join("; ", tax.errors().values()));
        }
        requireConsentWhenLive(page, holdPageForTax);

        Atin atin = ids.nextAtin();
        String gtin = barcode(r, page);
        Variant added = new Variant(UUID.randomUUID(), atin, ownerSku(page.owner().orgId(), atin), gtin, gtin == null,
                options, template.priceMinor(), template.currency(), 0, null, List.of(pictures), true,
                tax.variants().getFirst(), template.packaging());
        Product saved = attach(page, added, node, now);
        return new Added(saved, atin.value());
    }

    @Override
    public Moved moveVariant(Product source, String variantAtin, Product target, boolean holdTargetForTax, String by,
                             String reason, UUID changeRequestId, Instant now) {
        if (source.id().equals(target.id())) {
            throw invalid("The variant is already on that page");
        }
        if (target.status() == ProductStatus.MERGED || target.status() == ProductStatus.REJECTED) {
            throw invalid("The variant cannot move to a page that was merged or rejected");
        }
        Variant moving = variantOf(source, variantAtin);
        if (source.variants().size() < 2) {
            throw invalid("This is the only variant of its page. Merge the two pages instead of moving it.");
        }
        if (!moving.options().isEmpty() && target.variants().stream().anyMatch(v -> sameOptions(v.options(), moving.options()))) {
            throw invalid("The target page already has a variant with these options. Merge the pages, or reject the report.");
        }
        CategoryNode sourceNode = node(source);
        CategoryNode targetNode = node(target);
        boolean sameTax = sourceNode != null && targetNode != null && sourceNode.taxProfileId() != null
                && sourceNode.taxProfileId().equals(targetNode.taxProfileId());
        if (target.status() == ProductStatus.LIVE && !sameTax) {
            requireConsentWhenLive(target, holdTargetForTax);
        }
        Instant at = now;
        Product left = removeFrom(source, moving, at);
        Variant arrived = moving;
        if (!sameTax) {
            // Another tax profile: the old answer says nothing about the new category. It is evaluated again by the gate.
            arrived = moving.withTax(reevaluated(target, moving));
        }
        Product joined = attach(target, arrived, targetNode, at);
        publishMoved(source, left, joined, moving.atin().value(), "MOVE", by, reason, changeRequestId, at);
        return new Moved(left, joined, moving.atin().value());
    }

    @Override
    public Moved splitVariant(Product source, String variantAtin, String by, String reason, UUID changeRequestId,
                              Instant now) {
        Variant moving = variantOf(source, variantAtin);
        if (source.variants().size() < 2) {
            throw invalid("This is the only variant of its page, so there is nothing to split. Reject the report, or "
                    + "correct the barcode.");
        }
        if (source.categoryId() == null) {
            throw invalid("The page has no category yet, so a page for the variant cannot be created");
        }
        Product left = removeFrom(source, moving, now);
        Product page = source.splitOff(UUID.randomUUID(), ids.nextAtin(), moving, now);
        Product created = products.insert(page);
        productEvents.publish(CatalogEvents.PRODUCT_UPDATED, created);
        publishMoved(source, left, created, moving.atin().value(), "SPLIT", by, reason, changeRequestId, now);
        return new Moved(left, created, moving.atin().value());
    }

    /** The page without the variant; a page on sale keeps its status (the remaining variants were already resolved). */
    private Product removeFrom(Product page, Variant moving, Instant now) {
        List<Variant> rest = page.variants().stream().filter(v -> !v.id().equals(moving.id())).toList();
        Product saved = products.updateWithVariants(page.withContent(page.title(), page.attributes(), page.images(),
                page.details(), now).withVariants(rest));
        productEvents.publish(CatalogEvents.PRODUCT_UPDATED, saved);
        return saved;
    }

    /**
     * Adds the variant to the page and stores it. A LIVE page that no longer has a resolved decision on every variant goes
     * through the live gate (the only way a page leaves sale), and a LIVE one the gate cannot fault locally is reconciled
     * against tax-returns, which has no decision for a variant it has never classified.
     */
    private Product attach(Product page, Variant added, CategoryNode node, Instant now) {
        List<Variant> next = new ArrayList<>(page.variants());
        next.add(added);
        Product edited = page.withContent(page.title(), page.attributes(), page.images(), page.details(), now)
                .withVariants(next);
        if (edited.status() == ProductStatus.LIVE) {
            edited = liveGate.closeIfNotReady(edited, now);
        }
        Product saved = products.updateWithVariants(edited);
        if (saved.status() == ProductStatus.LIVE) {
            Product reconciled = liveGate.reconcile(saved.id());
            saved = reconciled == null ? saved : reconciled;
        }
        productEvents.publish(CatalogEvents.PRODUCT_UPDATED, saved);
        productEvents.publishTaxFactsChanged(saved, node, null, page.variants(), added.tax().facts().keySet());
        return saved;
    }

    private VariantTax reevaluated(Product target, Variant moving) {
        ProductType type = categories.findProductType(target.productTypeCode()).orElse(null);
        if (type == null) {
            return VariantTax.NONE.withFacts(moving.tax().facts());
        }
        return taxGate.evaluate(type, node(target), List.of(moving.tax().facts()), Collections.nCopies(1, null),
                target.details().sellerBehaviour()).variants().getFirst();
    }

    private void publishMoved(Product source, Product left, Product joined, String variantAtin, String kind, String by,
                              String reason, UUID changeRequestId, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", kind);
        p.put("variantAtin", variantAtin);
        p.put("fromProductId", source.id().toString());
        p.put("fromAtin", source.atin().value());
        p.put("toProductId", joined.id().toString());
        p.put("toAtin", joined.atin().value());
        p.put("toCategoryId", joined.categoryId() == null ? null : joined.categoryId().toString());
        p.put("toStatus", joined.status().name());
        p.put("movedBy", by);
        p.put("reason", reason);
        p.put("changeRequestId", changeRequestId == null ? null : changeRequestId.toString());
        p.put("occurredAt", now.toString());
        events.publish(CatalogEvents.VARIANT_MOVED, CatalogEvents.AGGREGATE_PRODUCT, joined.id().toString(), p);
    }

    private void requireConsentWhenLive(Product page, boolean consent) {
        if (page.status() == ProductStatus.LIVE && !consent) {
            throw new BusinessRuleException(ChangeRequestApplierImpl.NEEDS_TAX_REVIEW, "This page is on sale and the "
                    + "variant has no tax decision of its own yet. Approving takes the whole page off sale until tax review "
                    + "has approved it. Approve with 'hold the page for tax' if that is what you want, or reject the "
                    + "request with that reason.");
        }
    }

    /** The options of the new variant in the page's own dimensions and spelling; a combination the page has is refused. */
    private Map<String, String> newOptions(ProductChangeRequest r, Product page, ProductType type) {
        Map<String, String> asked = new LinkedHashMap<>();
        if (r.payload().get("options") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> asked.put(String.valueOf(k).strip(), String.valueOf(v).strip()));
        }
        VariantDraft canonical = VariantOptions.canonical(type, page.variationTheme(),
                List.of(new VariantDraft(asked, "", "", true, 0, 0))).getFirst();
        Map<String, String> options = canonical.options();
        Set<String> dimensions = page.variants().getFirst().options().keySet();
        if (options.values().stream().anyMatch(String::isBlank) || !options.keySet().equals(dimensions)) {
            throw invalid("Give a value for each of " + String.join(", ", dimensions));
        }
        if (page.variants().stream().anyMatch(v -> sameOptions(v.options(), options))) {
            throw invalid("The page already has a variant with these options");
        }
        return options;
    }

    /** The barcode of the new variant: a valid one nobody else uses, or none on a page whose variants have none either. */
    private String barcode(ProductChangeRequest r, Product page) {
        String typed = ChangeRequestPolicy.text(r.payload(), "gtin");
        boolean exempt = Boolean.parseBoolean(ChangeRequestPolicy.text(r.payload(), "gtinExempt"));
        boolean pageHasBarcodes = page.variants().stream().anyMatch(v -> v.gtin() != null && !v.gtin().isBlank());
        if (typed.isEmpty()) {
            if (exempt && !pageHasBarcodes) {
                return null;
            }
            throw invalid(pageHasBarcodes ? "Give the barcode of the new variant: the other variants of this page have one"
                    : "Say that the new variant has no barcode, like the others on this page");
        }
        GtinReader.Reading reading = GtinReader.read(typed, settings.current().reservedPrefixes());
        if (!reading.valid()) {
            throw invalid(reading.message());
        }
        products.findByVariantGtin(reading.digits()).ifPresent(existing -> {
            throw new GtinAlreadyUsed(reading.gtin14(), existing.atin().value());
        });
        return Gtin.digits(typed);
    }

    /** The pictures of the new variant, judged by media like any other picture on a page (S390). */
    private ProductImage[] variantPictures(ProductChangeRequest r, Product page) {
        if (!(r.payload().get("images") instanceof List<?> list) || list.isEmpty()) {
            return new ProductImage[0];
        }
        List<ProductImage> asked = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> payload = new LinkedHashMap<>();
                m.forEach((k, v) -> payload.put(String.valueOf(k), v));
                asked.add(ChangeRequestPolicy.imageOf(payload));
            }
        }
        if (asked.size() > VariantDraft.MAX_IMAGES) {
            throw invalid("At most " + VariantDraft.MAX_IMAGES + " pictures for one variant");
        }
        ImageGate.Outcome outcome = imageGate.check(List.of(), List.of(asked), page.productTypeCode(), Set.of());
        if (!outcome.errors().isEmpty()) {
            throw new BusinessRuleException(ChangeRequestApplierImpl.IMAGE_REJECTED,
                    outcome.errors().values().stream().collect(Collectors.joining(" ")));
        }
        return asked.toArray(new ProductImage[0]);
    }

    private CategoryNode node(Product page) {
        return page.categoryId() == null ? null : categories.findById(page.categoryId()).orElse(null);
    }

    private static Variant variantOf(Product page, String atin) {
        return page.variants().stream().filter(v -> v.atin().value().equals(atin)).findFirst()
                .orElseThrow(() -> invalid("That product is not a variant of the page"));
    }

    private static String ownerSku(String orgId, Atin atin) {
        return SellerSkus.generate(orgId, atin.value());
    }

    private static boolean sameOptions(Map<String, String> a, Map<String, String> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        return a.entrySet().stream().allMatch(e -> Objects.requireNonNull(e.getValue()).strip()
                .equalsIgnoreCase(b.get(e.getKey()).strip()));
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException(INVALID, message);
    }
}
