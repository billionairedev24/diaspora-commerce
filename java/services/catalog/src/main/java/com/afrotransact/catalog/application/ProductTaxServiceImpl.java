package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.TaxEvidence;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxEvidenceRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProductTaxServiceImpl implements ProductTaxService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductTaxServiceImpl.class);
    private static final int RECHECK_LIMIT = 500;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final TaxEvidenceRepository evidence;
    private final TaxGateEvaluator gate;
    private final ProductEvents events;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final Clock clock;

    ProductTaxServiceImpl(ProductRepository products, CategoryRepository categories, TaxEvidenceRepository evidence,
                          TaxGateEvaluator gate, ProductEvents events, PermissionChecker permissions,
                          ResourceAccessChecker access, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.evidence = evidence;
        this.gate = gate;
        this.events = events;
        this.permissions = permissions;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TaxReadiness readiness(UUID productId) {
        Product p = products.findById(productId).orElseThrow(() -> notFound(productId.toString()));
        ensureOwnerOrStaff(permissions.require(CatalogPermissions.WRITE), p);
        Product fresh = recheck(p);
        CategoryNode node = fresh.categoryId() == null ? null : categories.findById(fresh.categoryId()).orElse(null);
        return TaxReadiness.of(fresh, node == null ? null : node.taxProfileId(), node == null ? null : node.taxStatus());
    }

    /** Asks tax-returns again for every variant of the product and stores what changed. */
    private Product recheck(Product p) {
        if (p.categoryId() == null) {
            return p;
        }
        CategoryNode node = categories.findById(p.categoryId()).orElse(null);
        ProductType type = categories.findProductType(p.productTypeCode()).orElse(null);
        if (node == null || type == null) {
            return p;
        }
        TaxGateEvaluator.Evaluation e = gate.evaluate(type, node,
                p.variants().stream().map(v -> v.tax().facts()).toList(),
                p.variants().stream().map(Variant::tax).toList(), p.details().sellerBehaviour());
        List<Variant> next = new ArrayList<>();
        for (int i = 0; i < p.variants().size(); i++) {
            Variant v = p.variants().get(i);
            VariantTax fresh = e.variants().get(i);
            if (!fresh.equals(v.tax())) {
                products.saveVariantTax(v.id(), fresh);
            }
            next.add(v.withTax(fresh));
        }
        return p.withVariants(next);
    }

    @Override
    @Transactional
    public TaxEvidence uploadEvidence(String variantAtin, String factId, String fileName, String contentType,
                                      byte[] content) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        Atin atin = parseAtin(variantAtin);
        Product p = products.findByVariantAtin(atin).orElseThrow(() -> variantNotFound(variantAtin));
        ensureOwnerOrStaff(actor, p);
        access.requireOwned(CatalogPermissions.WRITE, CatalogRelations.CAN_EDIT, CatalogRelations.product(p.id()),
                p.owner().orgId());
        ProductType type = categories.findProductType(p.productTypeCode())
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type"));
        AttributeDefinition def = type.taxFacts().stream().filter(d -> d.key().equals(factId)).findFirst().orElse(null);
        if (def == null || def.kind() != AttributeKind.FILE || !def.evidence()) {
            throw new CatalogRuleViolation("This is not a question that needs a file",
                    Map.of("factId", "This product does not ask for a file for \"" + factId + "\""));
        }
        String problem = TaxEvidence.problem(contentType, content == null ? 0 : content.length);
        if (problem != null) {
            throw new CatalogRuleViolation(problem, Map.of("file", problem));
        }
        Variant variant = p.variants().stream().filter(v -> v.atin().equals(atin)).findFirst().orElseThrow();
        String name = fileName == null || fileName.isBlank() ? "evidence" : fileName.strip();
        TaxEvidence stored = evidence.store(new TaxEvidence(UUID.randomUUID(), atin.value(), p.owner().orgId(), factId,
                evidence.nextVersion(atin.value(), factId), name.length() > 200 ? name.substring(0, 200) : name,
                contentType.toLowerCase(Locale.ROOT), content.length, actor.subject(), Instant.now(clock)), content);
        Map<String, Object> facts = new LinkedHashMap<>(variant.tax().facts());
        facts.put(factId, stored.id().toString());
        products.saveVariantTax(variant.id(), variant.tax().withFacts(facts));
        Product fresh = recheck(products.findById(p.id()).orElseThrow());
        CategoryNode node = categories.findById(fresh.categoryId()).orElse(null);
        events.publishTaxFactsChanged(fresh, node, null, fresh.variants(), Set.of(factId), Set.of(atin.value()));
        return stored;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxEvidence> evidence(String variantAtin) {
        Atin atin = parseAtin(variantAtin);
        Product p = products.findByVariantAtin(atin).orElseThrow(() -> variantNotFound(variantAtin));
        ensureOwnerOrStaff(permissions.require(CatalogPermissions.WRITE), p);
        return evidence.findByVariant(atin.value());
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceFile evidenceFile(UUID evidenceId) {
        TaxEvidence meta = evidence.findById(evidenceId)
                .orElseThrow(() -> new NotFoundException("catalog.tax-evidence-not-found", "File not found"));
        Product p = products.findByVariantAtin(new Atin(meta.variantAtin()))
                .orElseThrow(() -> variantNotFound(meta.variantAtin()));
        ensureOwnerOrStaff(permissions.require(CatalogPermissions.WRITE), p);
        byte[] bytes = evidence.content(evidenceId).orElseThrow(() -> new NotFoundException(
                "catalog.tax-evidence-not-found", "File not found"));
        return new EvidenceFile(meta, bytes);
    }

    @Override
    @Transactional
    public void classificationChanged(String variantAtin, String status) {
        TaxStatus next = "APPROVED".equals(status) ? TaxStatus.READY : "STALE".equals(status) ? TaxStatus.PENDING_REVIEW
                : TaxStatus.parse(status);
        if (next == null || variantAtin == null) {
            return;
        }
        Atin atin;
        try {
            atin = new Atin(variantAtin.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return;
        }
        Optional<Product> product = products.findByVariantAtin(atin);
        if (product.isEmpty()) {
            return;
        }
        for (Variant v : product.get().variants()) {
            if (v.atin().equals(atin) && v.tax().status() != next) {
                products.saveVariantTax(v.id(), v.tax().withStatus(next));
                LOG.info("Variant {} tax status {} -> {} (tax-returns)", atin.value(), v.tax().status(), next);
            }
        }
    }

    @Override
    @Transactional
    public void profileStatusChanged(String categoryCode, String status) {
        if (categoryCode == null || status == null) {
            return;
        }
        categories.findByCode(categoryCode).filter(n -> n.taxProfileId() != null && !status.equals(n.taxStatus()))
                .ifPresent(n -> {
                    categories.update(n.withTax(n.taxProfileId(), status));
                    products.findByTaxProfile(n.taxProfileId(), RECHECK_LIMIT).forEach(this::recheck);
                });
    }

    private static Atin parseAtin(String raw) {
        try {
            return new Atin(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw variantNotFound(raw);
        }
    }

    private static void ensureOwnerOrStaff(Actor actor, Product p) {
        boolean staff = actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
        if (!staff && !actor.orgId().map(p.owner().orgId()::equals).orElse(false)) {
            throw notFound(p.id().toString());
        }
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("catalog.product-not-found", "Product " + id + " not found");
    }

    private static NotFoundException variantNotFound(String atin) {
        return new NotFoundException("catalog.variant-not-found", "Variant " + atin + " not found");
    }
}
