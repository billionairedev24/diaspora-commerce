package com.afrotransact.catalog.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.application.CatalogMatchService.MatchCommand;
import com.afrotransact.catalog.application.CatalogMatchService.MatchResult;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.TargetKey;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import org.springframework.stereotype.Component;

@Component
class ImportMatcherImpl implements ImportMatcher {

    private final CatalogMatchService matches;
    private final CandidateMatchService candidates;
    private final OfferEligibilityService eligibility;
    private final ProductRepository products;
    private final CategoryRepository categories;

    ImportMatcherImpl(CatalogMatchService matches, CandidateMatchService candidates, OfferEligibilityService eligibility,
                      ProductRepository products, CategoryRepository categories) {
        this.matches = matches;
        this.candidates = candidates;
        this.eligibility = eligibility;
        this.products = products;
        this.categories = categories;
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
            return fromMatch(matches.matchForOrganisation(new MatchCommand(key.gtin(), org), org));
        }
        if (key.atin() != null) {
            return byAtin(org, key.atin());
        }
        return byName(org, key);
    }

    /**
     * No barcode: the exact normalised name, brand and size inside the category links the row. Anything less is never
     * linked: the row is held out with the pages we found, so the seller chooses (never a fuzzy auto-match).
     */
    private OfferTarget byName(String org, TargetKey key) {
        UUID category = key.category() == null ? null : categories.findByPath(key.category()).map(CategoryNode::id).orElse(null);
        CandidateMatchService.RowMatch row = candidates.matchRow(org, key.searchText(), key.brand(), category);
        return switch (row.kind()) {
            case LINKED -> {
                String owner = products.findByVariantAtin(new Atin(row.atin())).map(pg -> pg.owner().orgId()).orElse(null);
                yield OfferTarget.match(row.atin(), row.parentAtin(), row.title(), owner, row.canOffer(),
                        blockers(row.blockers()));
            }
            case HELD -> OfferTarget.of(OfferTarget.Kind.AMBIGUOUS, "We found products that look like this one but are not "
                    + "sure which is yours: " + row.candidates().stream().map(ImportMatcherImpl::describe)
                    .collect(Collectors.joining("; ")) + ". Nothing was added for this row. Sell it from Add a product, or "
                    + "add the barcode to this row.");
            case NONE -> OfferTarget.of(OfferTarget.Kind.NO_MATCH, "We could not find this product on AfroTransact. Add it "
                    + "from Add a product or with the products template.");
        };
    }

    private static String describe(CandidateMatchService.Candidate c) {
        return c.title() + (c.sizeLabel() == null ? "" : " (" + c.sizeLabel() + ")");
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

    /** A match with the organisation that owns the page, so a CREATE row for the seller's own page is not turned into an offer. */
    private OfferTarget fromMatch(MatchResult r) {
        OfferTarget target = fromResult(r);
        if (target.kind() != OfferTarget.Kind.MATCH || target.ownerOrg() != null || target.atin() == null) {
            return target;
        }
        String owner = products.findByVariantAtin(new Atin(target.atin())).map(pg -> pg.owner().orgId()).orElse(null);
        return OfferTarget.match(target.atin(), target.parentAtin(), target.title(), owner, target.canOffer(),
                target.blockers());
    }

    private static OfferTarget fromResult(MatchResult r) {
        return switch (r.result()) {
            case NO_MATCH -> OfferTarget.of(OfferTarget.Kind.NO_MATCH, "We could not find this product on AfroTransact. "
                    + "Add it with the products template.");
            case INVALID, RESTRICTED -> OfferTarget.of(OfferTarget.Kind.INVALID, r.message() != null ? r.message()
                    : "That barcode does not look right");
            case MATCH -> OfferTarget.match(r.atin(), r.parentAtin(), r.title(), null, r.canOffer(), blockers(r.blockers()));
        };
    }

    private static List<OfferTarget.Blocker> blockers(List<Blocker> blockers) {
        return blockers.stream().map(b -> new OfferTarget.Blocker(b.code(), b.message())).toList();
    }
}
