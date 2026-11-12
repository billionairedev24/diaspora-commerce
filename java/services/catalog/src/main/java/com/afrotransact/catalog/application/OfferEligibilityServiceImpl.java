package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DepartmentNotAllowed;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.rules.RuleField;
import com.afrotransact.catalog.domain.tax.LiveRequiresTax;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellingApprovals.SellingUnavailable;
import com.afrotransact.catalog.port.SellingApprovals.Status;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferEligibilityServiceImpl implements OfferEligibilityService {

    static final String APPROVAL_CODE = "offers.selling-approval-required";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final ListingSuppressionRepository suppressions;
    private final SellerScopeGuard scopeGuard;
    private final SellingPublishGate gate;
    private final ListingRulesService rules;
    private final ActorResolver actors;
    private final String retailOrgId;

    OfferEligibilityServiceImpl(ProductRepository products, CategoryRepository categories, BrandRepository brands,
                                ListingSuppressionRepository suppressions, SellerScopeGuard scopeGuard,
                                SellingPublishGate gate, ListingRulesService rules, ActorResolver actors,
                                @Value("${at.catalog.retail-org-id}") String retailOrgId) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.suppressions = suppressions;
        this.scopeGuard = scopeGuard;
        this.gate = gate;
        this.rules = rules;
        this.actors = actors;
        this.retailOrgId = retailOrgId;
    }

    @Override
    @Transactional(readOnly = true)
    public Eligibility eligibility(String variantAtin, String sellerOrgId) {
        Actor actor = actors.current();
        if (!actor.can(CatalogPermissions.READ) && !actor.can(CatalogPermissions.WRITE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + CatalogPermissions.READ + ", " + CatalogPermissions.WRITE);
        }
        Atin atin = parse(variantAtin);
        Product product = products.findByVariantAtin(atin).orElseThrow(() -> notFound(variantAtin));
        Variant variant = product.variants().stream().filter(v -> v.atin().equals(atin)).findFirst()
                .orElseThrow(() -> notFound(variantAtin));
        boolean trusted = actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
        String org = trusted ? blankToNull(sellerOrgId) : actor.orgId().orElse(null);
        return evaluate(product, variant, org);
    }

    @Override
    @Transactional(readOnly = true)
    public Eligibility evaluate(Product product, Variant variant, String sellerOrgId) {
        List<Blocker> blockers = new ArrayList<>();
        CategoryNode node = product.categoryId() == null ? null : categories.findById(product.categoryId()).orElse(null);
        if (!onSale(product)) {
            blockers.add(new Blocker("catalog.page-not-live",
                    "This product page is not on sale yet, so you cannot add an offer to it."));
        }
        if (brandUnavailable(product)) {
            blockers.add(new Blocker("catalog.brand-unavailable",
                    "This brand is not available on AfroTransact, so you cannot sell this product."));
        }
        if (sellerOrgId != null && node != null) {
            ListingOwner seller = new ListingOwner(retailOrgId.equals(sellerOrgId) ? ListingOwnerKind.PLATFORM
                    : ListingOwnerKind.SELLER, sellerOrgId);
            boolean inScope = true;
            try {
                scopeGuard.scopeOf(seller).require(node);
            } catch (DepartmentNotAllowed ex) {
                inScope = false;
                blockers.add(new Blocker(ex.code(), ex.getMessage()));
            }
            if (inScope && gate.enabled()) {
                approvalBlocker(seller, product).ifPresent(blockers::add);
            }
        }
        return new Eligibility(variant.atin().value(), product.atin().value(), blockers.isEmpty(), blockers,
                allowedConditions(node), product.owner().orgId());
    }

    private Optional<Blocker> approvalBlocker(ListingOwner seller, Product product) {
        try {
            Status status = gate.evaluate(seller, gate.factsOf(product));
            if (status.sell()) {
                return Optional.empty();
            }
            String message = status.reason() == null || status.reason().isBlank()
                    ? "You need approval to sell this brand. Apply under Listings." : status.reason();
            return Optional.of(new Blocker(APPROVAL_CODE, message));
        } catch (SellingUnavailable ex) {
            return Optional.of(new Blocker("catalog.selling-unavailable",
                    "We could not check whether your store may sell this. Try again in a minute."));
        }
    }

    /** LIVE, every variant with a resolved tax decision and not hidden by compliance enforcement. */
    private boolean onSale(Product p) {
        return p.status() == ProductStatus.LIVE && LiveRequiresTax.holds(p)
                && !suppressions.isHidden(p.owner().orgId());
    }

    private boolean brandUnavailable(Product p) {
        String id = p.attributes().get(BrandKeys.ID);
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            return brands.findById(UUID.fromString(id)).map(OfferEligibilityServiceImpl::retiredOrRejected).orElse(false);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean retiredOrRejected(Brand b) {
        return b.status() == BrandStatus.RETIRED || b.status() == BrandStatus.REJECTED;
    }

    /** Used items are allowed where the category's rules show the condition field (listing rule D9); else NEW only. */
    private List<ItemCondition> allowedConditions(CategoryNode node) {
        boolean usedAllowed = node != null && rules.forNode(node).shown(RuleField.CONDITION);
        return usedAllowed ? Arrays.asList(ItemCondition.values()) : List.of(ItemCondition.NEW);
    }

    private static Atin parse(String raw) {
        try {
            return new Atin(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw notFound(raw);
        }
    }

    private static NotFoundException notFound(String atin) {
        return new NotFoundException("catalog.variant-not-found", "No product variant " + atin);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
