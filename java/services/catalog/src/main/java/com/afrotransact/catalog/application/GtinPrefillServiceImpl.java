package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.prefill.CategoryHintPolicy;
import com.afrotransact.catalog.domain.prefill.ProductData;
import com.afrotransact.catalog.domain.prefill.ProductPrefill;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductDataCache;
import com.afrotransact.catalog.port.ProductDataSource;
import com.afrotransact.catalog.port.ProductDataSourceException;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class GtinPrefillServiceImpl implements GtinPrefillService {

    private static final Logger LOG = LoggerFactory.getLogger(GtinPrefillServiceImpl.class);
    private static final int MAX_HINTS = 3;

    private final List<ProductDataSource> sources;
    private final ProductDataCache cache;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CategoryHintPolicy hints;
    private final ProductDataSettings settings;
    private final ActorResolver actors;
    private final Clock clock;

    GtinPrefillServiceImpl(List<ProductDataSource> sources, ProductDataCache cache, ProductRepository products,
                           CategoryRepository categories, CategoryHintPolicy hints, ProductDataSettings settings,
                           ActorResolver actors, Clock clock) {
        List<String> order = settings.order().stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(s -> {
                    int i = order.indexOf(s.name().toUpperCase(Locale.ROOT));
                    return i < 0 ? Integer.MAX_VALUE : i;
                }))
                .toList();
        this.cache = cache;
        this.products = products;
        this.categories = categories;
        this.hints = hints;
        this.settings = settings;
        this.actors = actors;
        this.clock = clock;
    }

    @Override
    public GtinPrefill prefill(String raw) {
        Actor actor = actors.current();
        if (!actor.can(CatalogPermissions.READ) && !actor.can(CatalogPermissions.WRITE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing one of the permissions " + CatalogPermissions.READ + ", " + CatalogPermissions.WRITE);
        }
        String digits = Gtin.digits(raw);
        if (!Gtin.isValid(digits)) {
            String fix = Gtin.suggestCorrection(digits);
            String message = digits.isEmpty() ? "Required" : fix == null ? "Use 8, 12, 13 or 14 digits"
                    : "Invalid check digit (did you mean " + fix + "?)";
            throw new CatalogRuleViolation("Invalid GTIN: " + message, Map.of("gtin", message));
        }
        Gtin gtin = new Gtin(digits);
        Optional<String> existing = products.findByVariantGtin(digits).map(p -> p.atin().value());
        if (existing.isPresent()) {
            return new GtinPrefill(digits, false, existing.get(), null, null, null, null, List.of(), Map.of(),
                    List.of(), List.of(), false, "Matches existing product " + existing.get()
                    + " — add your offer to it");
        }
        Instant now = Instant.now(clock);
        Optional<List<ProductData>> cached = cache.find(gtin, now);
        List<ProductData> results = cached.orElseGet(() -> lookup(gtin, now));
        ProductPrefill merged = ProductPrefill.merge(results);
        if (!merged.found()) {
            return new GtinPrefill(digits, false, null, null, null, null, null, List.of(), Map.of(), List.of(),
                    List.of(), cached.isPresent(), "No product data found for " + digits + " — enter the details");
        }
        return new GtinPrefill(digits, true, null, merged.title(), merged.brand(), merged.netContent(),
                merged.description(), merged.imageUrls(), merged.importedFrom(), categoryHints(merged),
                merged.sources(), cached.isPresent(), "Imported from " + String.join(" and ", merged.sources())
                + " — check every field before you submit");
    }

    /**
     * Asks the enabled providers in order until the prefill is complete. Provider failures are skipped (and the
     * answer is then not cached as a miss, so a later lookup retries them).
     */
    private List<ProductData> lookup(Gtin gtin, Instant now) {
        List<ProductData> results = new ArrayList<>();
        boolean failed = false;
        for (ProductDataSource source : sources) {
            if (!source.enabled()) {
                continue;
            }
            try {
                source.lookup(gtin).ifPresent(results::add);
            } catch (ProductDataSourceException e) {
                failed = true;
                LOG.warn("Product data lookup of {} in {} failed: {}", gtin.value(), source.name(), e.getMessage());
            }
            if (complete(ProductPrefill.merge(results))) {
                break;
            }
        }
        if (!results.isEmpty()) {
            cache.store(gtin, results, now, now.plus(settings.cacheTtl()));
        } else if (!failed) {
            cache.store(gtin, results, now, now.plus(settings.missTtl()));
        }
        return results;
    }

    private static boolean complete(ProductPrefill p) {
        return p.title() != null && p.brand() != null && p.netContent() != null && !p.imageUrls().isEmpty();
    }

    private List<CategoryHint> categoryHints(ProductPrefill p) {
        if (p.sourceCategories().isEmpty()) {
            return List.of();
        }
        Map<String, CategoryNode> byPath = categories.findAllActive().stream()
                .collect(Collectors.toMap(CategoryNode::path, Function.identity(), (a, b) -> a));
        List<CategoryHint> out = new ArrayList<>();
        for (CategoryHintPolicy.Match m : hints.match(p.sourceCategories(), MAX_HINTS)) {
            CategoryNode node = byPath.get(m.path());
            if (node != null) {
                out.add(new CategoryHint(node.id(), node.path(), node.level().name(), node.productTypeCode(),
                        m.matchedOn()));
            }
        }
        return List.copyOf(out);
    }
}
