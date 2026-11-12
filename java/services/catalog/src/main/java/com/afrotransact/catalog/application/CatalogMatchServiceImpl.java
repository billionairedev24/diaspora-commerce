package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.port.BrandRepository;
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
    private final BrandRepository brands;
    private final CategoryRepository categories;
    private final ChangeRequestRepository changeRequests;
    private final OfferEligibilityService eligibility;
    private final CatalogSettingsService settings;
    private final MatchMetrics metrics;
    private final ActorResolver actors;

    CatalogMatchServiceImpl(ProductRepository products, BrandRepository brands, CategoryRepository categories,
                            ChangeRequestRepository changeRequests, OfferEligibilityService eligibility,
                            CatalogSettingsService settings, MatchMetrics metrics, ActorResolver actors) {
        this.products = products;
        this.brands = brands;
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

    private MatchResult lookup(MatchCommand cmd, String org, CatalogPolicy policy) {
        boolean hasGtin = cmd.gtin() != null && !cmd.gtin().isBlank();
        boolean hasBrandMpn = cmd.brand() != null && !cmd.brand().isBlank() && cmd.mpn() != null
                && !cmd.mpn().isBlank();
        if (!hasGtin && !hasBrandMpn) {
            throw new BusinessRuleException("catalog.match-input-missing",
                    "Give the barcode, or the brand and the part number");
        }
        if (hasGtin) {
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
                return matched(p, v, MatchedOn.GTIN, reading, org);
            }
            if (hasBrandMpn) {
                Optional<MatchResult> byMpn = byBrandAndMpn(cmd, org, policy, reading);
                if (byMpn.isPresent()) {
                    return byMpn.get();
                }
            }
            return noMatch(reading);
        }
        return byBrandAndMpn(cmd, org, policy, null).orElseGet(() -> noMatch(null));
    }

    private Optional<MatchResult> byBrandAndMpn(MatchCommand cmd, String org, CatalogPolicy policy,
                                                GtinReader.Reading reading) {
        String mpnKey = mpnKey(cmd.mpn());
        if (mpnKey == null) {
            return Optional.empty();
        }
        Optional<Brand> brand = resolveBrand(cmd.brand());
        if (brand.isEmpty()) {
            return Optional.empty();
        }
        List<Product> pages = products.findByBrandAndMpn(brand.get().id().toString(), policy.mpnAttributeKey(), mpnKey)
                .stream().filter(p -> cmd.categoryId() == null || cmd.categoryId().equals(p.categoryId())).toList();
        Optional<Product> page = pages.stream().filter(p -> p.status() == ProductStatus.LIVE).findFirst()
                .or(() -> pages.stream().findFirst());
        if (page.isEmpty() || page.get().variants().isEmpty()) {
            return Optional.empty();
        }
        Product p = page.get();
        Variant only = p.variants().size() == 1 ? p.variants().getFirst() : null;
        return Optional.of(matched(p, only, MatchedOn.BRAND_MPN, reading, org));
    }

    private MatchResult matched(Product p, Variant v, MatchedOn on, GtinReader.Reading reading, String org) {
        Variant first = v != null ? v : p.variants().getFirst();
        OfferEligibilityService.Eligibility e = eligibility.evaluate(p, first, org);
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
        return new MatchResult(Result.MATCH, on, reading == null ? null : reading.gtin14(),
                reading == null ? null : reading.digits(), v == null ? null : v.atin().value(), p.atin().value(),
                p.status().name(), p.title(), p.attributes().get(BrandKeys.NAME),
                image == null ? null : new PageImage(image.url(), image.width(), image.height()),
                node == null ? null : node.path(), p.categoryId(), v == null ? Map.of() : v.options(), siblings,
                e.canOffer(), e.blockers(), false, create, null, null);
    }

    private MatchResult noMatch(GtinReader.Reading reading) {
        List<Blocker> create = new ArrayList<>();
        if (reading != null && changeRequests.existsOpenWrongMatch(reading.gtin14())) {
            create.add(new Blocker("catalog.wrong-match-open", "We are checking this barcode. We will tell you within "
                    + settings.current().reviewSlaBusinessDays() + " business days."));
        }
        return new MatchResult(Result.NO_MATCH, null, reading == null ? null : reading.gtin14(),
                reading == null ? null : reading.digits(), null, null, null, null, null, null, null, null, Map.of(),
                List.of(), false, List.of(), create.isEmpty(), create, null, null);
    }

    private static MatchResult refused(Result result, GtinReader.Reading reading, String code) {
        return new MatchResult(result, null, reading.gtin14(), reading.digits().isEmpty() ? null : reading.digits(),
                null, null, null, null, null, null, null, null, Map.of(), List.of(), false, List.of(), false,
                List.of(new Blocker(code, reading.message())), reading.suggestion(), reading.message());
    }

    private Optional<Brand> resolveBrand(String raw) {
        String value = raw.trim();
        try {
            return brands.findById(UUID.fromString(value));
        } catch (IllegalArgumentException notAnId) {
            return brands.findLiveByKey(BrandNames.normalise(value)).stream().findFirst();
        }
    }

    private static ProductImage firstImage(Product p, Variant v) {
        if (v != null && !v.images().isEmpty()) {
            return v.images().getFirst();
        }
        return p.images().isEmpty() ? null : p.images().getFirst();
    }

    /** Only letters and digits count, upper case: the same key the database index uses. */
    static String mpnKey(String mpn) {
        String key = mpn == null ? "" : mpn.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return key.isEmpty() ? null : key;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
