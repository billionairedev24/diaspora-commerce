package com.afrotransact.catalog.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.catalog.api.CatalogLookupApi;
import com.afrotransact.catalog.application.BakeryClaimService;
import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.IdentifierService;
import com.afrotransact.catalog.application.OfferEligibilityService;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link CatalogLookupApi}: the use cases behind the catalog reads that offers and seller
 * make over HTTP today, entered under the calling module's principal. It adds no rule and no permission of its own: the
 * application services check the caller's scopes exactly as they check a token's. It refuses to run without a caller, and when
 * the actor in force is not that caller.
 */
@Component
class CatalogLookupApiAdapter implements CatalogLookupApi {

    private final PublicCatalogService catalog;
    private final IdentifierService identifiers;
    private final OfferEligibilityService eligibility;
    private final CategoryService categories;
    private final BakeryClaimService bakery;
    private final ModuleEntry entry;

    CatalogLookupApiAdapter(PublicCatalogService catalog, IdentifierService identifiers,
                            OfferEligibilityService eligibility, CategoryService categories, BakeryClaimService bakery,
                            ActorResolver actors) {
        this.catalog = catalog;
        this.identifiers = identifiers;
        this.eligibility = eligibility;
        this.categories = categories;
        this.bakery = bakery;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Optional<LiveVariant> liveVariant(String variantAtin) {
        return entry.run(() -> {
            try {
                PublicCatalogService.LiveVariant live = catalog.liveVariant(variantAtin);
                return Optional.of(variant(live.product(), live.variant()));
            } catch (NotFoundException ex) {
                return Optional.<LiveVariant>empty();
            }
        });
    }

    @Override
    public Optional<LiveVariant> liveVariantByGtin(String gtin) {
        return entry.run(() -> {
            try {
                IdentifierService.GtinCheck check = identifiers.check(gtin, false);
                if (!check.valid() || check.existingAtin() == null) {
                    return Optional.<LiveVariant>empty();
                }
                Product product = catalog.liveProduct(check.existingAtin());
                String wanted = stripZeros(gtin);
                return product.variants().stream().filter(v -> v.gtin() != null && stripZeros(v.gtin()).equals(wanted))
                        .findFirst().map(v -> variant(product, v));
            } catch (NotFoundException ex) {
                return Optional.<LiveVariant>empty();
            }
        });
    }

    @Override
    public Optional<OfferEligibility> offerEligibility(String variantAtin, String sellerOrgId) {
        return entry.run(() -> {
            try {
                OfferEligibilityService.Eligibility e = eligibility.eligibility(variantAtin, sellerOrgId);
                return Optional.of(new OfferEligibility(e.canOffer(), e.blockers().stream()
                        .map(b -> new Blocker(b.code(), b.message())).toList(),
                        e.allowedConditions().stream().map(Enum::name).toList()));
            } catch (NotFoundException ex) {
                return Optional.<OfferEligibility>empty();
            }
        });
    }

    @Override
    public List<Department> departments() {
        return entry.run(() -> categories.tree(false).stream()
                .filter(n -> n.level() == CategoryLevel.DEPARTMENT && n.code() != null && !n.code().isBlank())
                .map(n -> new Department(n.code(), n.name(), n.compliance())).toList());
    }

    @Override
    public List<String> bakeryClaimOrganisations() {
        return entry.run(bakery::organisations);
    }

    private static LiveVariant variant(Product product, Variant variant) {
        return new LiveVariant(product.id(), product.atin().value(), variant.atin().value(), variant.gtin(),
                product.categoryId(), product.title(), product.owner().orgId());
    }

    private static String stripZeros(String gtin) {
        String digits = gtin.replaceAll("[^0-9]", "");
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}
