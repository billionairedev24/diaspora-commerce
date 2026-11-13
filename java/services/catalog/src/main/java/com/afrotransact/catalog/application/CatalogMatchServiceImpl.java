package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.MatchMetrics;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogMatchServiceImpl implements CatalogMatchService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ChangeRequestRepository changeRequests;
    private final OfferEligibilityService eligibility;
    private final CatalogSettingsService settings;
    private final MatchMetrics metrics;
    private final ActorResolver actors;

    CatalogMatchServiceImpl(ProductRepository products, CategoryRepository categories,
                            ChangeRequestRepository changeRequests, OfferEligibilityService eligibility,
                            CatalogSettingsService settings, MatchMetrics metrics, ActorResolver actors) {
        this.products = products;
        this.categories = categories;
        this.changeRequests = changeRequests;
        this.eligibility = eligibility;
        this.settings = settings;
        this.metrics = metrics;
        this.actors = actors;
    }

    @Override
    @Transactional(readOnly = true)
    public MatchResult match(MatchCommand cmd) {
        Actor actor = actors.current();
        if (!actor.can(CatalogPermissions.READ) && !actor.can(CatalogPermissions.WRITE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + CatalogPermissions.READ + ", " + CatalogPermissions.WRITE);
        }
        boolean trusted = actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
        String org = trusted ? blankToNull(cmd.sellerOrgId()) : actor.orgId().orElse(null);
        CatalogPolicy policy = settings.current();
        MatchResult result = lookup(cmd, org, policy);
        metrics.matched(result.result().name());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public MatchResult matchForOrganisation(MatchCommand cmd, String sellerOrgId) {
        MatchResult result = lookup(cmd, blankToNull(sellerOrgId), settings.current());
        metrics.matched(result.result().name());
        return result;
    }

    private MatchResult lookup(MatchCommand cmd, String org, CatalogPolicy policy) {
        if (cmd.gtin() == null || cmd.gtin().isBlank()) {
            throw new BusinessRuleException("catalog.match-input-missing", "Give the barcode");
        }
        GtinReader.Reading reading = GtinReader.read(cmd.gtin(), policy.reservedPrefixes());
        if (reading.status() == GtinReader.Status.RESTRICTED) {
            return refused(Result.RESTRICTED, reading, "catalog.gtin-restricted");
        }
        if (!reading.valid()) {
            return refused(Result.INVALID, reading, "catalog.gtin-invalid");
        }
        Optional<Product> page = products.findByVariantGtin(reading.digits());
        if (page.isPresent()) {
            Product p = page.get();
            Variant v = p.variants().stream()
                    .filter(x -> x.gtin() != null && GtinReader.key14(Gtin.digits(x.gtin())).equals(reading.gtin14()))
                    .findFirst().orElse(p.variants().getFirst());
            return matched(p, v, reading, org);
        }
        return noMatch(reading);
    }

    private MatchResult matched(Product p, Variant v, GtinReader.Reading reading, String org) {
        OfferEligibilityService.Eligibility e = eligibility.evaluate(p, v, org);
        CategoryNode node = p.categoryId() == null ? null : categories.findById(p.categoryId()).orElse(null);
        ProductImage image = firstImage(p, v);
        List<MatchVariant> siblings = new ArrayList<>();
        for (Variant x : p.variants()) {
            siblings.add(new MatchVariant(x.atin().value(), x.options(),
                    x.gtin() == null || x.gtin().isBlank() ? null : GtinReader.key14(Gtin.digits(x.gtin()))));
        }
        List<Blocker> create = new ArrayList<>();
        create.add(new Blocker("catalog.gtin-exists", "This product is already on AfroTransact. Add your offer to it "
                + "instead of creating a new page."));
        return new MatchResult(Result.MATCH, reading.gtin14(), reading.digits(), v.atin().value(), p.atin().value(),
                p.status().name(), p.title(), p.attributes().get(BrandKeys.NAME),
                image == null ? null : new PageImage(image.url(), image.width(), image.height()),
                node == null ? null : node.path(), p.categoryId(), v.options(), siblings,
                e.canOffer(), e.blockers(), false, create, null, null);
    }

    private MatchResult noMatch(GtinReader.Reading reading) {
        List<Blocker> create = new ArrayList<>();
        if (changeRequests.existsOpenWrongMatch(reading.gtin14())) {
            create.add(new Blocker("catalog.wrong-match-open", "We are checking this barcode. We will tell you within "
                    + settings.current().reviewSlaBusinessDays() + " business days."));
        }
        return new MatchResult(Result.NO_MATCH, reading.gtin14(), reading.digits(), null, null, null, null, null, null, null, null, Map.of(),
                List.of(), false, List.of(), create.isEmpty(), create, null, null);
    }

    private static MatchResult refused(Result result, GtinReader.Reading reading, String code) {
        return new MatchResult(result, reading.gtin14(), reading.digits().isEmpty() ? null : reading.digits(),
                null, null, null, null, null, null, null, null, Map.of(), List.of(), false, List.of(), false,
                List.of(new Blocker(code, reading.message())), reading.suggestion(), reading.message());
    }

    private static ProductImage firstImage(Product p, Variant v) {
        if (v != null && !v.images().isEmpty()) {
            return v.images().getFirst();
        }
        return p.images().isEmpty() ? null : p.images().getFirst();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
