package com.afrotransact.catalog.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.application.CatalogMatchService.MatchCommand;
import com.afrotransact.catalog.application.CatalogMatchService.MatchResult;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.TargetKey;
import com.afrotransact.catalog.port.ProductRepository;
import org.springframework.stereotype.Component;

@Component
class ImportMatcherImpl implements ImportMatcher {

    private final CatalogMatchService matches;
    private final OfferEligibilityService eligibility;
    private final ProductRepository products;

    ImportMatcherImpl(CatalogMatchService matches, OfferEligibilityService eligibility, ProductRepository products) {
        this.matches = matches;
        this.eligibility = eligibility;
        this.products = products;
    }

    @Override
    public Map<String, OfferTarget> resolve(String sellerOrgId, Collection<TargetKey> keys) {
        Map<String, OfferTarget> out = new LinkedHashMap<>();
        for (TargetKey key : keys) {
            if (key.present()) {
                out.computeIfAbsent(key.id(), id -> one(sellerOrgId, key));
            }
        }
        return out;
    }

    private OfferTarget one(String org, TargetKey key) {
        if (key.gtin() != null) {
            return fromMatch(matches.matchForOrganisation(new MatchCommand(key.gtin(), null, null, null, org), org));
        }
        if (key.atin() != null) {
            return byAtin(org, key.atin());
        }
        return fromMatch(matches.matchForOrganisation(new MatchCommand(null, key.brand(), key.mpn(), null, org), org));
    }

    private OfferTarget byAtin(String org, String atinText) {
        Atin atin;
        try {
            atin = new Atin(atinText);
        } catch (IllegalArgumentException ex) {
            return OfferTarget.of(OfferTarget.Kind.INVALID, "That ATIN does not look right");
        }
        Optional<Product> page = products.findByVariantAtin(atin);
        if (page.isEmpty()) {
            return OfferTarget.of(OfferTarget.Kind.NO_MATCH, "We could not find a product with that ATIN");
        }
        Product p = page.get();
        Variant v = p.variants().stream().filter(x -> x.atin().equals(atin)).findFirst().orElse(null);
        if (v == null) {
            return OfferTarget.of(OfferTarget.Kind.NO_MATCH, "We could not find a product with that ATIN");
        }
        OfferEligibilityService.Eligibility e = eligibility.evaluate(p, v, org);
        return OfferTarget.match(v.atin().value(), p.atin().value(), p.title(), p.owner().orgId(), e.canOffer(),
                blockers(e.blockers()));
    }

    private static OfferTarget fromMatch(MatchResult r) {
        return switch (r.result()) {
            case NO_MATCH -> OfferTarget.of(OfferTarget.Kind.NO_MATCH, "We could not find this product on AfroTransact. "
                    + "Add it with the products template.");
            case INVALID, RESTRICTED -> OfferTarget.of(OfferTarget.Kind.INVALID, r.message() != null ? r.message()
                    : "That barcode does not look right");
            case MATCH -> r.atin() == null
                    ? OfferTarget.of(OfferTarget.Kind.AMBIGUOUS, "This part number is on a page with several sizes or "
                            + "colours. Add the barcode or the ATIN of the one you sell.")
                    : OfferTarget.match(r.atin(), r.parentAtin(), r.title(), null, r.canOffer(), blockers(r.blockers()));
        };
    }

    private static List<OfferTarget.Blocker> blockers(List<Blocker> blockers) {
        return blockers.stream().map(b -> new OfferTarget.Blocker(b.code(), b.message())).toList();
    }
}
