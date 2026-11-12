package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.BusinessDays;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.DuplicateHints;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GtinExemptionServiceImpl implements GtinExemptionService {

    private static final int HINT_PAGES = 50;
    private static final String BRAND_GATED = "brand_gated";

    private final ProductRepository products;
    private final GtinExemptionRepository exemptions;
    private final CatalogSettingsService settings;
    private final ChangeRequestEvents events;
    private final ProductEvents productEvents;
    private final PermissionChecker permissions;
    private final Clock clock;

    GtinExemptionServiceImpl(ProductRepository products, GtinExemptionRepository exemptions,
                             CatalogSettingsService settings, ChangeRequestEvents events, ProductEvents productEvents,
                             PermissionChecker permissions, Clock clock) {
        this.products = products;
        this.exemptions = exemptions;
        this.settings = settings;
        this.events = events;
        this.productEvents = productEvents;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExemptionRequirement> requirement(CategoryRules rules, String productTypeCode,
                                                      Map<String, String> attributes) {
        CatalogPolicy policy = settings.current();
        boolean gated = BRAND_GATED.equals(rules.approval()) || policy.exemptionGatedCategoryCodes().contains(rules.code())
                || policy.exemptionGatedCategoryCodes().contains(productTypeCode);
        if (gated) {
            return Optional.of(ExemptionRequirement.BRAND_GATED_CATEGORY);
        }
        String brandId = attributes.get(BrandKeys.ID);
        if (policy.exemptionRequiresApprovalForKnownBrands() && brandId != null && !brandId.isBlank()
                && products.countPagesWithBarcode(brandId) >= policy.knownGs1BrandMinPages()) {
            return Optional.of(ExemptionRequirement.KNOWN_GS1_BRAND);
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> pageWithSamePartNumber(Map<String, String> attributes) {
        String brandId = attributes.get(BrandKeys.ID);
        String mpn = DuplicateHints.key(attributes.get(settings.current().mpnAttributeKey()));
        if (brandId == null || brandId.isBlank() || mpn == null) {
            return Optional.empty();
        }
        return products.findByBrandAndMpn(brandId, settings.current().mpnAttributeKey(), mpn).stream().findFirst();
    }

    @Override
    @Transactional
    public GtinExemptionRequest requested(Product page, String sellerReason, ExemptionRequirement requirement) {
        Instant now = Instant.now(clock);
        GtinExemptionRequest saved = exemptions.insert(GtinExemptionRequest.filed(page.id(), page.atin().value(),
                page.owner().orgId(), sellerReason == null || sellerReason.isBlank() ? null : sellerReason.strip(),
                requirement, now, BusinessDays.add(now, settings.current().reviewSlaBusinessDays())));
        events.exemptionRequested(saved, now);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExemptionView> mine() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        String org = actor.orgId().orElseThrow(() -> new ForbiddenActionException("catalog.no-org",
                "Only seller organisations have exemption requests"));
        Instant now = Instant.now(clock);
        return exemptions.findByOrg(org).stream().map(r -> view(r, now, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<ExemptionView> queue(ExemptionStatus status, int page, int size) {
        permissions.require(CatalogPermissions.MODERATE);
        GtinExemptionRepository.Page found = exemptions.search(status, Math.max(page, 0), Math.clamp(size, 1, 100));
        Instant now = Instant.now(clock);
        return new PageView<>(found.items().stream().map(r -> view(r, now, true)).toList(), found.total(), found.page(),
                found.size());
    }

    @Override
    @Transactional
    public ExemptionView decide(UUID id, boolean approve, String reason) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        GtinExemptionRequest request = exemptions.findById(id).orElseThrow(() -> new NotFoundException(
                "catalog.exemption-not-found", "Exemption request " + id + " not found"));
        if (!request.open()) {
            throw decided();
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < ProductChangeRequest.MIN_REASON) {
            throw new CatalogRuleViolation("The decision needs a reason", Map.of("reason", "At least "
                    + ProductChangeRequest.MIN_REASON + " characters"));
        }
        Instant now = Instant.now(clock);
        Product page = products.findById(request.productId()).orElseThrow(() -> new NotFoundException(
                "catalog.product-not-found", "The page of the request no longer exists"));
        GtinExemptionRequest next = approve ? request.approved(actor.subject(), why, now)
                : request.rejected(actor.subject(), why, now);
        GtinExemptionRequest saved;
        try {
            saved = exemptions.update(next);
        } catch (StaleVersionException ex) {
            throw decided();
        }
        if (approve) {
            productEvents.publish(CatalogEvents.PRODUCT_UPDATED, products.update(page.exemptionApproved(now)));
        } else {
            productEvents.publish(CatalogEvents.PRODUCT_REJECTED, products.update(page.exemptionRejected(why, now)));
        }
        events.exemptionDecided(saved, now);
        return view(saved, now, true);
    }

    private ExemptionView view(GtinExemptionRequest r, Instant now, boolean withHints) {
        Product page = products.findById(r.productId()).orElse(null);
        String mpnKey = settings.current().mpnAttributeKey();
        List<DuplicateHints.Hint> hints = List.of();
        if (withHints && page != null && r.open()) {
            String brandId = page.attributes().get(BrandKeys.ID);
            hints = brandId == null || brandId.isBlank() ? List.of()
                    : DuplicateHints.of(page, products.findByBrand(brandId, HINT_PAGES), mpnKey);
        }
        boolean overdue = r.open() && r.slaDueAt() != null && now.isAfter(r.slaDueAt());
        return new ExemptionView(r, page == null ? null : page.title(),
                page == null ? null : page.attributes().get(BrandKeys.NAME),
                page == null ? null : page.attributes().get(mpnKey), hints, overdue,
                BusinessDays.hoursBetween(r.requestedAt(), now), r.open() ? GtinExemptionRequest.SELLER_MESSAGE : null);
    }

    private static ConflictException decided() {
        return new ConflictException("catalog.exemption-decided", "This request was already decided");
    }
}
