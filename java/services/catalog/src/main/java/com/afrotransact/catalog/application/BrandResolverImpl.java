package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandKeys;
import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.brand.UnbrandedReason;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import org.springframework.stereotype.Component;

@Component
class BrandResolverImpl implements BrandResolver {

    /** A seller cannot flood the review queue. */
    static final int MAX_PENDING_PER_ORG = 100;
    private static final String FIELD = "attributes.brand";

    private final BrandRepository brands;
    private final CategoryRepository categories;
    private final Clock clock;

    BrandResolverImpl(BrandRepository brands, CategoryRepository categories, Clock clock) {
        this.brands = brands;
        this.categories = categories;
        this.clock = clock;
    }

    @Override
    public Map<String, String> resolve(ListingOwner owner, CategoryNode node, ProductType type,
                                       Map<String, String> attributes, Map<String, String> current) {
        Map<String, String> out = new LinkedHashMap<>(attributes);
        String name = blankToNull(out.remove(BrandKeys.NAME));
        String id = blankToNull(out.remove(BrandKeys.ID));
        String reason = blankToNull(out.remove(BrandKeys.REASON));
        Optional<AttributeDefinition> def = type.brandAttribute();
        if (def.isEmpty()) {
            return out;
        }
        if (name != null && id == null) {
            Optional<UnbrandedReason> typedReason = UnbrandedReason.ofText(name);
            if (typedReason.isPresent()) {
                reason = typedReason.get().name();
                name = null;
            }
        }
        if (id != null) {
            Brand brand = chosen(owner, node, id, blankToNull(current.get(BrandKeys.ID)));
            out.put(BrandKeys.NAME, brand.name());
            out.put(BrandKeys.ID, brand.id().toString());
        } else if (name != null) {
            String clean = BrandNames.clean(name, FIELD);
            if (node == null) {
                out.put(BrandKeys.NAME, clean);
            } else {
                BrandScopes scopes = new BrandScopes(categories.findAll());
                Matched match = matchOrCreate(owner.orgId(), scopes.covering(node),
                        Set.of(scopes.department(node).id()), clean);
                out.put(BrandKeys.NAME, match.brand().name());
                out.put(BrandKeys.ID, match.brand().id().toString());
            }
        } else if (reason != null) {
            UnbrandedReason r = UnbrandedReason.ofCode(reason).orElseThrow(
                    () -> new CatalogRuleViolation("Unknown reason for having no brand", Map.of(FIELD, "Unknown")));
            if (!def.get().unbrandedReasons().contains(r)) {
                throw new CatalogRuleViolation(def.get().label() + " cannot be left out for this category",
                        Map.of(FIELD, "Name the brand; this category does not allow items without one"));
            }
            out.put(BrandKeys.REASON, r.name());
        }
        return out;
    }

    @Override
    public Matched matchOrCreate(String orgId, Set<UUID> covering, Set<UUID> departments, String name) {
        String clean = BrandNames.clean(name, "name");
        String key = BrandNames.normalise(clean);
        brands.lockKey(key);
        for (Brand b : brands.findLiveByKey(key)) {
            if (b.offeredTo(orgId) && covers(b, covering)) {
                return new Matched(b, false);
            }
        }
        if (brands.countPendingOf(orgId) >= MAX_PENDING_PER_ORG) {
            throw new CatalogRuleViolation("Too many brands are waiting for review",
                    Map.of(FIELD, "You have " + MAX_PENDING_PER_ORG + " brands awaiting review; use an existing brand"));
        }
        Brand created = brands.insert(Brand.create(clean, BrandStatus.PENDING, orgId, departments, Instant.now(clock)));
        return new Matched(created, true);
    }

    private Brand chosen(ListingOwner owner, CategoryNode node, String id, String currentId) {
        UUID wanted;
        try {
            wanted = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CatalogRuleViolation("Unknown brand", Map.of(FIELD, "Unknown brand"));
        }
        Brand brand = brands.findById(wanted)
                .orElseThrow(() -> new CatalogRuleViolation("Unknown brand", Map.of(FIELD, "Unknown brand")));
        for (int hops = 0; brand.status() == BrandStatus.MERGED_INTO && brand.mergedIntoId() != null && hops < 8; hops++) {
            brand = brands.findById(brand.mergedIntoId()).orElse(brand);
        }
        boolean held = currentId != null && (currentId.equals(wanted.toString()) || currentId.equals(brand.id().toString()));
        if (brand.status() == BrandStatus.REJECTED) {
            throw new CatalogRuleViolation("That brand was not accepted", Map.of(FIELD, "Not accepted; choose another"));
        }
        if (held) {
            return brand;
        }
        if (!brand.offeredTo(owner.orgId())) {
            throw new CatalogRuleViolation("That brand is not available", Map.of(FIELD, "Not available"));
        }
        if (node != null) {
            Set<UUID> covering = new BrandScopes(categories.findAll()).covering(node);
            if (!covers(brand, covering)) {
                throw new CatalogRuleViolation("That brand is not sold in this category",
                        Map.of(FIELD, "Not sold in this category"));
            }
        }
        return brand;
    }

    private static boolean covers(Brand brand, Set<UUID> covering) {
        Set<UUID> hit = new LinkedHashSet<>(brand.scope());
        hit.retainAll(covering);
        return !hit.isEmpty();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
