package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelDraft;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.LabelKind;
import com.afrotransact.catalog.domain.barcode.LabelPolicy;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.domain.barcode.UnitLabelCode;
import com.afrotransact.catalog.port.IdentifierIssuer;
import com.afrotransact.catalog.port.LabelRenderer;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.UnitLabelRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LabelServiceImpl implements LabelService {

    private final LabelPolicy policy;
    private final Map<LabelFormat, LabelRenderer> renderers;
    private final ProductRepository products;
    private final UnitLabelRepository unitLabels;
    private final IdentifierIssuer issuer;
    private final LabelSettings settings;
    private final ActorResolver actors;
    private final Clock clock;

    LabelServiceImpl(LabelPolicy policy, List<LabelRenderer> renderers, ProductRepository products,
                     UnitLabelRepository unitLabels, IdentifierIssuer issuer, LabelSettings settings,
                     ActorResolver actors, Clock clock) {
        this.policy = policy;
        Map<LabelFormat, LabelRenderer> byFormat = new EnumMap<>(LabelFormat.class);
        renderers.forEach(r -> byFormat.put(r.format(), r));
        this.renderers = byFormat;
        this.products = products;
        this.unitLabels = unitLabels;
        this.issuer = issuer;
        this.settings = settings;
        this.actors = actors;
        this.clock = clock;
    }

    @Override
    public RenderedLabel render(LabelDraft draft, LabelFormat format, SheetLayout layout) {
        requireLabelPermission();
        Label label = policy.build(draft);
        return output(List.of(label), format, layout, fileName(label));
    }

    @Override
    public RenderedLabel renderBatch(List<LabelDraft> drafts, LabelFormat format, SheetLayout layout) {
        requireLabelPermission();
        if (drafts == null || drafts.isEmpty() || drafts.size() > MAX_BATCH) {
            throw new CatalogRuleViolation("A batch holds 1–" + MAX_BATCH + " labels",
                    Map.of("labels", "1–" + MAX_BATCH + " labels"));
        }
        LabelRenderer renderer = renderer(format);
        if (!renderer.supportsBatch()) {
            throw new CatalogRuleViolation(format + " holds one label; use PDF or ZPL for a batch",
                    Map.of("format", "PDF or ZPL"));
        }
        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            try {
                labels.add(policy.build(drafts.get(i)));
            } catch (CatalogRuleViolation e) {
                Map<String, String> fields = new LinkedHashMap<>();
                int index = i;
                e.fieldErrors().forEach((k, v) -> fields.put("labels[" + index + "]." + k, v));
                throw new CatalogRuleViolation("Label " + (i + 1) + ": " + e.getMessage(), fields);
            }
        }
        return output(labels, format, layout, "labels");
    }

    @Override
    @Transactional
    public RenderedLabel unitLabel(String atin, String ownerOrgId, LabelFormat format, int copies) {
        Actor actor = actors.current();
        boolean service = actor.type() == PrincipalType.SERVICE || actor.type() == PrincipalType.STAFF;
        if (!actor.can(CatalogPermissions.WRITE) && !(service && actor.can(CatalogPermissions.READ))) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + CatalogPermissions.WRITE + " (services: " + CatalogPermissions.READ + ")");
        }
        Atin id = new Atin(atin == null ? "" : atin.strip().toUpperCase(Locale.ROOT));
        Product product = products.findByVariantAtin(id).or(() -> products.findByAtin(id))
                .orElseThrow(() -> notFound(id.value()));
        Variant variant = variant(product, id);
        String owner = stockOwner(actor, product, ownerOrgId);
        String gtin = variant.gtin();
        boolean vendorGtin = gtin != null && Gtin.isValid(gtin);
        LabelDraft draft;
        String unitCode = null;
        if (owner.equals(settings.retailOrgId()) && vendorGtin) {
            draft = new LabelDraft(LabelKind.UNIT, null, null, gtin, null, null, null, null, null, null, null,
                    "MANUFACTURER BARCODE", product.title(), lines(variant), copies);
        } else {
            UnitLabelCode code = unitLabels.find(owner, variant.atin())
                    .orElseGet(() -> unitLabels.insertIfAbsent(issuer.nextUnitLabelCode(), owner, variant.atin(),
                            Instant.now(clock)));
            draft = new LabelDraft(LabelKind.UNIT, null, code.value(), null, null, null, null, null, null, null,
                    null, "AFROTRANSACT UNIT LABEL", product.title(), lines(variant), copies);
            unitCode = code.value();
        }
        Label label = policy.build(draft);
        return output(List.of(label), format, SheetLayout.LABEL_PAGES, "unit-" + variant.atin().value())
                .withUnitCode(unitCode);
    }

    private RenderedLabel output(List<Label> labels, LabelFormat format, SheetLayout layout, String name) {
        LabelRenderer renderer = renderer(format);
        int printed = labels.stream().mapToInt(Label::copies).sum();
        if (printed > MAX_PRINTED) {
            throw new CatalogRuleViolation("At most " + MAX_PRINTED + " labels per request (copies included)",
                    Map.of("copies", "At most " + MAX_PRINTED + " in total"));
        }
        byte[] content = renderer.render(labels, layout == null ? SheetLayout.LABEL_PAGES : layout);
        return new RenderedLabel(renderer.contentType(), name + "." + format.name().toLowerCase(Locale.ROOT), content,
                renderer.supportsBatch() ? printed : 1);
    }

    private LabelRenderer renderer(LabelFormat format) {
        LabelRenderer r = renderers.get(format == null ? LabelFormat.SVG : format);
        if (r == null) {
            throw new IllegalArgumentException("Unsupported label format " + format);
        }
        return r;
    }

    /** The variant with this ATIN, or the only variant of a product ATIN. */
    private static Variant variant(Product product, Atin id) {
        Optional<Variant> exact = product.variants().stream().filter(v -> v.atin().equals(id)).findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        if (product.variants().size() == 1) {
            return product.variants().getFirst();
        }
        throw new CatalogRuleViolation("Product " + id.value() + " has " + product.variants().size()
                + " variants; print the unit label of a variant ATIN", Map.of("atin", "Use a variant ATIN"));
    }

    /**
     * Staff and service clients print for {@code requested} (default: the product's owner); sellers print for their
     * own organisation, on their own products or on live product pages they can sell on.
     */
    private static String stockOwner(Actor actor, Product product, String requested) {
        if (actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE) {
            return requested == null || requested.isBlank() ? product.owner().orgId() : requested.strip();
        }
        String org = actor.orgId().orElseThrow(() -> new ForbiddenActionException("catalog.no-org",
                "Only seller organisations print unit labels"));
        if (requested != null && !requested.isBlank() && !requested.strip().equals(org)) {
            throw new ForbiddenActionException("catalog.other-org", "Sellers print unit labels for their own stock");
        }
        if (!org.equals(product.owner().orgId()) && product.status() != ProductStatus.LIVE) {
            throw notFound(product.atin().value());
        }
        return org;
    }

    private static List<String> lines(Variant v) {
        String options = v.optionLabel();
        return List.of(options.isBlank() ? v.atin().value() : v.atin().value() + " · " + options);
    }

    private void requireLabelPermission() {
        Actor actor = actors.current();
        if (!actor.can(CatalogPermissions.READ) && !actor.can(CatalogPermissions.WRITE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + CatalogPermissions.READ + ", " + CatalogPermissions.WRITE);
        }
    }

    private static String fileName(Label label) {
        String data = label.symbol().humanReadable().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        String base = label.kind().name().toLowerCase(Locale.ROOT) + (data.isEmpty() ? "" : "-" + data);
        return base.length() > 60 ? base.substring(0, 60) : base;
    }

    private static NotFoundException notFound(String atin) {
        return new NotFoundException("catalog.product-not-found", "Product " + atin + " not found");
    }
}
