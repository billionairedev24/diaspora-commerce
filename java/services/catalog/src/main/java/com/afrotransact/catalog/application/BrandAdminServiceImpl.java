package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandNames;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import com.afrotransact.catalog.domain.brand.BrandUsage;
import com.afrotransact.catalog.domain.taxonomy.AuditSubject;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyRules;
import com.afrotransact.catalog.port.BrandRepository;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BrandAdminServiceImpl implements BrandAdminService {

    private static final int MAX_PAGE = 200;
    private static final int MAX_BULK = 500;

    private final BrandRepository brands;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final TaxonomyAuditRepository audit;
    private final ProductEvents productEvents;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final Clock clock;

    BrandAdminServiceImpl(BrandRepository brands, CategoryRepository categories, ProductRepository products,
                          TaxonomyAuditRepository audit, ProductEvents productEvents, PermissionChecker permissions,
                          DomainEventPublisher events, Clock clock) {
        this.brands = brands;
        this.categories = categories;
        this.products = products;
        this.audit = audit;
        this.productEvents = productEvents;
        this.permissions = permissions;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public BrandPage list(BrandFilter f) {
        permissions.require(CatalogPermissions.TAXONOMY);
        int limit = Math.clamp(f.limit() <= 0 ? 50 : f.limit(), 1, MAX_PAGE);
        int offset = Math.max(0, f.offset());
        String needle = BrandNames.normalise(f.q());
        List<BrandUsage> rows = brands.search(f.statuses(), needle, f.scopeNodeId(), limit, offset);
        Map<UUID, CategoryNode> nodes = nodes();
        Map<UUID, String> names = mergedNames(rows.stream().map(BrandUsage::brand).toList());
        Map<BrandStatus, Long> counts = new EnumMap<>(BrandStatus.class);
        for (BrandStatus s : BrandStatus.values()) {
            counts.put(s, 0L);
        }
        brands.countByStatus().forEach(c -> counts.put(c.status(), c.count()));
        return new BrandPage(rows.stream().map(u -> view(u, nodes, names)).toList(),
                brands.count(f.statuses(), needle, f.scopeNodeId()), counts);
    }

    @Override
    @Transactional(readOnly = true)
    public BrandView get(UUID id) {
        permissions.require(CatalogPermissions.TAXONOMY);
        BrandUsage usage = brands.usage(id).orElseThrow(BrandAdminServiceImpl::notFound);
        return view(usage, nodes(), mergedNames(List.of(usage.brand())));
    }

    // ------------------------------------------------------------------ creating

    @Override
    @Transactional
    public BrandView create(String name, List<UUID> scope, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        String clean = BrandNames.clean(name, "name");
        Set<UUID> nodes = scopeOf(scope);
        Brand clash = clash(clean, nodes, null);
        if (clash != null) {
            throw exists(clash);
        }
        Brand saved = brands.insert(Brand.create(clean, BrandStatus.APPROVED, null, nodes, Instant.now(clock))
                .decided(BrandStatus.APPROVED, null, actor.subject(), why, Instant.now(clock)));
        record(actor, "BRAND_CREATED", saved, why, Map.of("scope", paths(saved.scope())));
        return get(saved.id());
    }

    @Override
    @Transactional
    public BulkResult createMany(List<String> names, List<UUID> scope, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        Set<UUID> nodes = scopeOf(scope);
        if (names == null || names.isEmpty()) {
            throw new CatalogRuleViolation("Paste at least one brand", Map.of("names", "Required"));
        }
        if (names.size() > MAX_BULK) {
            throw new CatalogRuleViolation("Add at most " + MAX_BULK + " brands at a time",
                    Map.of("names", "At most " + MAX_BULK));
        }
        List<BulkOutcome> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int created = 0;
        int existing = 0;
        int invalid = 0;
        List<String> createdNames = new ArrayList<>();
        for (String raw : names) {
            String clean;
            try {
                clean = BrandNames.clean(raw, "name");
            } catch (CatalogRuleViolation e) {
                results.add(new BulkOutcome(raw == null ? "" : raw.trim(), "INVALID", null, e.getMessage()));
                invalid++;
                continue;
            }
            String key = BrandNames.normalise(clean);
            if (!seen.add(key)) {
                results.add(new BulkOutcome(clean, "EXISTS", null, "Listed twice"));
                existing++;
                continue;
            }
            Brand clash = clash(clean, nodes, null);
            if (clash != null) {
                results.add(new BulkOutcome(clean, "EXISTS", clash.id(), "Already listed as " + clash.name()));
                existing++;
                continue;
            }
            Instant now = Instant.now(clock);
            Brand saved = brands.insert(Brand.create(clean, BrandStatus.APPROVED, null, nodes, now)
                    .decided(BrandStatus.APPROVED, null, actor.subject(), why, now));
            results.add(new BulkOutcome(saved.name(), "CREATED", saved.id(), null));
            createdNames.add(saved.name());
            created++;
        }
        if (created > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("brands", createdNames);
            details.put("scope", paths(nodes));
            writeAudit(actor, "BRANDS_ADDED", "bulk", created + " brands", why, details);
            changed("BRANDS_ADDED", "bulk", created + " brands", 0);
        }
        return new BulkResult(results, created, existing, invalid);
    }

    // ------------------------------------------------------------------ decisions

    @Override
    @Transactional
    public BrandView approve(UUID id, Long expectedVersion, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        Brand brand = locked(id, expectedVersion);
        if (brand.status() == BrandStatus.APPROVED) {
            throw new ConflictException("catalog.brand-state", brand.name() + " is already approved");
        }
        if (brand.status() == BrandStatus.MERGED_INTO) {
            throw new ConflictException("catalog.brand-state", brand.name() + " was merged into another brand");
        }
        Brand clash = clash(brand.name(), brand.scope(), brand.id());
        if (clash != null && clash.status() == BrandStatus.APPROVED) {
            throw new ConflictException("catalog.brand-exists", "There is already an approved brand " + clash.name()
                    + " in this scope. Merge " + brand.name() + " into it instead.");
        }
        Brand saved = brands.update(brand.decided(BrandStatus.APPROVED, null, actor.subject(), why, Instant.now(clock)));
        record(actor, "BRAND_APPROVED", saved, why, Map.of("from", brand.status().name()));
        return get(saved.id());
    }

    @Override
    @Transactional
    public BrandView reject(UUID id, Long expectedVersion, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        Brand brand = locked(id, expectedVersion);
        if (brand.status() != BrandStatus.PENDING) {
            throw new ConflictException("catalog.brand-state", "Only brands awaiting review can be rejected");
        }
        Brand saved = brands.update(brand.decided(BrandStatus.REJECTED, null, actor.subject(), why, Instant.now(clock)));
        record(actor, "BRAND_REJECTED", saved, why, Map.of("products", brands.usage(id).orElseThrow().products()));
        return get(saved.id());
    }

    @Override
    @Transactional
    public BrandView retire(UUID id, Long expectedVersion, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        Brand brand = locked(id, expectedVersion);
        if (brand.status() != BrandStatus.APPROVED) {
            throw new ConflictException("catalog.brand-state", "Only approved brands can be retired");
        }
        Brand saved = brands.update(brand.decided(BrandStatus.RETIRED, null, actor.subject(), why, Instant.now(clock)));
        record(actor, "BRAND_RETIRED", saved, why, Map.of("products", brands.usage(id).orElseThrow().products()));
        return get(saved.id());
    }

    @Override
    @Transactional
    public BrandView merge(UUID id, Long expectedVersion, UUID targetId, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        Brand source = locked(id, expectedVersion);
        if (targetId == null) {
            throw new CatalogRuleViolation("Choose the brand to merge into", Map.of("targetId", "Required"));
        }
        if (targetId.equals(id)) {
            throw new CatalogRuleViolation("A brand cannot be merged into itself", Map.of("targetId", "Choose another brand"));
        }
        if (source.status() == BrandStatus.MERGED_INTO) {
            throw new ConflictException("catalog.brand-state", source.name() + " was already merged");
        }
        Brand target = brands.findById(targetId).orElseThrow(BrandAdminServiceImpl::notFound);
        if (target.status() != BrandStatus.APPROVED) {
            throw new CatalogRuleViolation("Merge into an approved brand", Map.of("targetId", "Must be approved"));
        }
        Instant now = Instant.now(clock);
        for (Brand child : brands.findMergedInto(source.id())) {
            brands.update(child.decided(BrandStatus.MERGED_INTO, target.id(), actor.subject(), why, now));
        }
        List<UUID> moved = brands.repointProducts(source.id(), target.id(), target.name());
        Set<UUID> union = new LinkedHashSet<>(target.scope());
        union.addAll(source.scope());
        if (!union.equals(target.scope())) {
            brands.update(target.withScope(union, now));
        }
        Brand saved = brands.update(source.decided(BrandStatus.MERGED_INTO, target.id(), actor.subject(), why, now));
        republish(moved);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("into", target.name());
        details.put("intoId", target.id().toString());
        details.put("products", moved.size());
        record(actor, "BRAND_MERGED", saved, why, details);
        return get(saved.id());
    }

    // ------------------------------------------------------------------ editing

    @Override
    @Transactional
    public BrandView update(UUID id, Long expectedVersion, String name, List<UUID> scope, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        Brand brand = locked(id, expectedVersion);
        if (brand.status() == BrandStatus.MERGED_INTO) {
            throw new ConflictException("catalog.brand-state", brand.name() + " was merged; edit " + "the brand it was merged into");
        }
        Brand next = brand;
        Map<String, Object> details = new LinkedHashMap<>();
        Instant now = Instant.now(clock);
        boolean renamed = false;
        if (name != null) {
            String clean = BrandNames.clean(name, "name");
            if (!clean.equals(brand.name())) {
                next = next.renamed(clean, now);
                renamed = true;
                details.put("name", Map.of("from", brand.name(), "to", clean));
            }
        }
        if (scope != null) {
            Set<UUID> nodes = scopeOf(scope);
            if (!nodes.equals(brand.scope())) {
                next = next.withScope(nodes, now);
                details.put("scope", Map.of("from", paths(brand.scope()), "to", paths(nodes)));
            }
        }
        if (details.isEmpty()) {
            throw new CatalogRuleViolation("Nothing to change", Map.of("name", "Nothing changed"));
        }
        Brand clash = clash(next.name(), next.scope(), brand.id());
        if (clash != null && clash.status().live()) {
            throw exists(clash);
        }
        Brand saved = brands.update(next);
        if (renamed) {
            republish(brands.repointProducts(saved.id(), saved.id(), saved.name()));
        }
        record(actor, "BRAND_UPDATED", saved, why, details);
        return get(saved.id());
    }

    // ------------------------------------------------------------------ helpers

    private Brand locked(UUID id, Long expectedVersion) {
        Brand brand = brands.findById(id).orElseThrow(BrandAdminServiceImpl::notFound);
        Versions.check(expectedVersion, brand.version(), "brand");
        return brand;
    }

    /** A live brand with this name whose scope overlaps (one covers the other), other than {@code ignore}. */
    private Brand clash(String name, Set<UUID> scope, UUID ignore) {
        String key = BrandNames.normalise(name);
        brands.lockKey(key);
        BrandScopes scopes = new BrandScopes(categories.findAll());
        Set<UUID> mine = coveringAll(scopes, scope);
        for (Brand other : brands.findLiveByKey(key)) {
            if (other.id().equals(ignore)) {
                continue;
            }
            Set<UUID> theirs = coveringAll(scopes, other.scope());
            boolean overlaps = other.scope().stream().anyMatch(mine::contains) || scope.stream().anyMatch(theirs::contains);
            if (overlaps) {
                return other;
            }
        }
        return null;
    }

    private Set<UUID> coveringAll(BrandScopes scopes, Set<UUID> nodes) {
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID n : nodes) {
            categories.findById(n).ifPresent(node -> out.addAll(scopes.covering(node)));
        }
        return out;
    }

    private Set<UUID> scopeOf(List<UUID> scope) {
        if (scope == null || scope.isEmpty()) {
            throw new CatalogRuleViolation("Choose where the brand applies", Map.of("scope", "Choose at least one"));
        }
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID id : scope) {
            CategoryNode node = categories.findById(id).orElseThrow(
                    () -> new CatalogRuleViolation("Unknown category in the scope", Map.of("scope", "Unknown category")));
            if (!node.active()) {
                throw new CatalogRuleViolation(node.path() + " is retired", Map.of("scope", node.path() + " is retired"));
            }
            out.add(id);
        }
        return out;
    }

    private void republish(List<UUID> productIds) {
        for (UUID productId : productIds) {
            products.findById(productId).ifPresent(p -> productEvents.publish(CatalogEvents.PRODUCT_UPDATED, p));
        }
    }

    private Map<UUID, CategoryNode> nodes() {
        Map<UUID, CategoryNode> out = new HashMap<>();
        categories.findAll().forEach(n -> out.put(n.id(), n));
        return out;
    }

    private List<String> paths(Set<UUID> ids) {
        Map<UUID, CategoryNode> nodes = nodes();
        return ids.stream().map(i -> nodes.get(i) == null ? i.toString() : nodes.get(i).path()).sorted().toList();
    }

    private Map<UUID, String> mergedNames(List<Brand> rows) {
        Set<UUID> targets = new LinkedHashSet<>();
        rows.forEach(b -> {
            if (b.mergedIntoId() != null) {
                targets.add(b.mergedIntoId());
            }
        });
        Map<UUID, String> out = new HashMap<>();
        brands.findAllById(targets).forEach(b -> out.put(b.id(), b.name()));
        return out;
    }

    private static BrandView view(BrandUsage usage, Map<UUID, CategoryNode> nodes, Map<UUID, String> mergedNames) {
        List<ScopeNode> scope = usage.brand().scope().stream().map(nodes::get).filter(n -> n != null)
                .sorted((a, b) -> a.path().compareTo(b.path()))
                .map(n -> new ScopeNode(n.id(), n.path(), n.level().name())).toList();
        return new BrandView(usage, mergedNames.get(usage.brand().mergedIntoId()), scope);
    }

    private void record(Actor actor, String action, Brand brand, String reason, Map<String, Object> details) {
        writeAudit(actor, action, brand.id().toString(), brand.name(), reason, details);
        changed(action, brand.id().toString(), brand.name(), brand.version() + 1);
    }

    private void writeAudit(Actor actor, String action, String subjectId, String label, String reason,
                            Map<String, Object> details) {
        audit.append(new TaxonomyAuditEntry(UUID.randomUUID(), Instant.now(clock), actor.subject(), action,
                AuditSubject.BRAND, subjectId, label, reason, details));
    }

    private void changed(String action, String subjectId, String label, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("subjectKind", AuditSubject.BRAND.name());
        payload.put("subjectId", subjectId);
        payload.put("label", label);
        payload.put("version", version);
        events.publish(CatalogEvents.TAXONOMY_CHANGED, CatalogEvents.AGGREGATE_TAXONOMY, subjectId, payload);
    }

    private static ConflictException exists(Brand clash) {
        return new ConflictException("catalog.brand-exists", clash.name() + " already exists in this scope");
    }

    private static NotFoundException notFound() {
        return new NotFoundException("catalog.brand-not-found", "Brand not found");
    }
}
